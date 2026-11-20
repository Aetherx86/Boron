package dev.aetherx86.boron.processor

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo

private const val FAST_DIFF = "dev.aetherx86.boron.FastDiff"
private const val BITPACK = "dev.aetherx86.boron.Bitpack"
private const val BITS = "dev.aetherx86.boron.Bits"
private const val DIFF_IGNORE = "dev.aetherx86.boron.DiffIgnore"

class BoronProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        BoronProcessor(environment.codeGenerator, environment.logger)
}

/**
 * One input class produces one output file, so everything is declared non-aggregating. KSP then
 * re-runs only for a changed file rather than for the whole module.
 *
 * A symbol that refers to a type this or another processor has yet to generate fails validation.
 * It is returned from [process], and KSP retries it in the next round.
 */
class BoronProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = ArrayList<KSAnnotated>()
        val run = { name: String, generate: (KSClassDeclaration) -> Unit ->
            for (symbol in resolver.getSymbolsWithAnnotation(name)) {
                if (!symbol.validate(enableNewFeatures = true)) {
                    deferred.add(symbol)
                    continue
                }
                if (symbol !is KSClassDeclaration) {
                    logger.error("this annotation only applies to classes", symbol)
                    continue
                }
                try {
                    generate(symbol)
                } catch (e: Rejected) {
                    logger.error(e.message.orEmpty(), e.node)
                }
            }
        }
        run(FAST_DIFF, ::generateDiff)
        run(BITPACK, ::generateBitpack)
        return deferred
    }

    /** A problem in user code, reported at the place that caused it. */
    private open class Rejected(message: String, val node: KSNode) : Exception(message)

    /** A `@Bitpack` class that contains itself, directly or through other `@Bitpack` classes. */
    private class Cycle(message: String, node: KSNode) : Rejected(message, node)

    private fun KSClassDeclaration.generatedVisibility(): KModifier {
        var current: KSClassDeclaration? = this
        var internal = false
        while (current != null) {
            when (current.getVisibility()) {
                Visibility.PUBLIC -> {}
                Visibility.INTERNAL -> internal = true
                else -> throw Rejected(
                    "${current.simpleName.asString()} must be public or internal, because " +
                        "generated code lives in its own file",
                    current,
                )
            }
            current = current.parentDeclaration as? KSClassDeclaration
        }
        return if (internal) KModifier.INTERNAL else KModifier.PUBLIC
    }

    /** Flattens nesting into one name, so `Outer.Inner` becomes `Outer_Inner`. */
    private fun KSClassDeclaration.flatName(): String =
        toClassName().simpleNames.joinToString("_")

    private fun KSAnnotated.hasAnnotation(name: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == name }

    /**
     * Follows type aliases to the type they stand for. `typealias Celsius = Double` must compare
     * like a `Double`, and a nullable use of an alias stays nullable.
     */
    private fun KSType.expanded(): KSType {
        var type = this
        var nullable = isMarkedNullable
        var steps = 0
        while (steps++ < 64) {
            val alias = type.declaration as? KSTypeAlias ?: break
            type = alias.type.resolve()
            nullable = nullable || type.isMarkedNullable
        }
        return if (nullable) type.makeNullable() else type
    }

    /**
     * The properties in declaration order. For a class with a primary constructor, an annotation
     * on a constructor parameter counts too, because `@Bits(3) val x: Int` in that position
     * applies to the parameter.
     */
    private fun KSClassDeclaration.parameterAnnotation(property: String, annotation: String) =
        primaryConstructor?.parameters
            ?.firstOrNull { it.name?.asString() == property }
            ?.annotations
            ?.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == annotation
            }

    // ---- FastDiff ----

    private fun generateDiff(declaration: KSClassDeclaration) {
        if (declaration.classKind != ClassKind.CLASS) {
            throw Rejected("@FastDiff only applies to classes", declaration)
        }
        val visibility = declaration.generatedVisibility()
        val properties = declaration.getAllProperties()
            .filter { property ->
                val name = property.simpleName.asString()
                !property.hasAnnotation(DIFF_IGNORE) &&
                    declaration.parameterAnnotation(name, DIFF_IGNORE) == null
            }
            .toList()
        if (properties.isEmpty()) {
            throw Rejected("@FastDiff needs at least one property to compare", declaration)
        }
        if (properties.size > 64) {
            throw Rejected(
                "@FastDiff supports at most 64 properties, because the mask is a Long, but " +
                    "${declaration.simpleName.asString()} has ${properties.size}",
                declaration,
            )
        }
        for (property in properties) {
            if (property.isPrivate()) {
                throw Rejected(
                    "generated code cannot read the private property " +
                        "${property.simpleName.asString()}. Annotate it with @DiffIgnore to skip it",
                    property,
                )
            }
        }
        val flat = declaration.flatName()
        // Two properties can map to one constant name, for example `fooBar` and `foo_bar`, and a
        // property called `all` maps to the reserved `ALL`. Either would otherwise surface as a
        // redeclaration error inside the generated file.
        val constantOwners = HashMap<String, String>()
        for (property in properties) {
            val name = property.simpleName.asString()
            val constant = screamingCase(name)
            if (constant == "ALL") {
                throw Rejected(
                    "property $name would generate the constant ${flat}Diff.ALL, which is reserved " +
                        "for the mask of every property. Rename it or annotate it with @DiffIgnore",
                    property,
                )
            }
            val previous = constantOwners.put(constant, name)
            if (previous != null) {
                throw Rejected(
                    "properties $previous and $name both generate the constant ${flat}Diff.$constant. " +
                        "Rename one of them or annotate it with @DiffIgnore",
                    property,
                )
            }
        }

        val resolver = declaration.typeParameters.toTypeParameterResolver()
        val typeVariables = declaration.typeParameters.map { it.toTypeVariableName(resolver) }
        val className = declaration.toClassName()
        val selfType: TypeName = if (typeVariables.isEmpty()) {
            className
        } else {
            className.parameterizedBy(typeVariables)
        }

        val maskType = if (properties.size <= 32) INT else LONG
        val one = if (maskType == INT) "1" else "1L"
        val zero = if (maskType == INT) "0" else "0L"
        val file = declaration.containingFile
        val packageName = declaration.packageName.asString()

        val constants = TypeSpec.objectBuilder("${flat}Diff").addModifiers(visibility)
        file?.let { constants.addOriginatingKSFile(it) }
        for ((i, property) in properties.withIndex()) {
            constants.addProperty(
                PropertySpec.builder(screamingCase(property.simpleName.asString()), maskType)
                    .addModifiers(KModifier.CONST)
                    .initializer("%L·shl·%L", one, i)
                    .build(),
            )
        }
        val allBits = if (properties.size == 64) -1L else (1L shl properties.size) - 1
        constants.addProperty(
            PropertySpec.builder("ALL", maskType)
                .addModifiers(KModifier.CONST)
                .initializer(if (maskType == INT) "%L" else "%LL", if (maskType == INT) allBits.toInt() else allBits)
                .build(),
        )
        constants.addFunction(
            FunSpec.builder("names")
                .addKdoc("The names of the properties set in [mask]. Allocates, so it is meant for logging.\n")
                .addParameter("mask", maskType)
                .returns(LIST.parameterizedBy(STRING))
                .addCode(
                    CodeBlock.builder().apply {
                        add("return buildList {\n")
                        indent()
                        for ((i, property) in properties.withIndex()) {
                            add(
                                "if·(mask·and·(%L·shl·%L)·!=·%L)·add(%S)\n",
                                one, i, zero, property.simpleName.asString(),
                            )
                        }
                        unindent()
                        add("}\n")
                    }.build(),
                )
                .build(),
        )

        val diff = FunSpec.builder("diffMask")
            .addModifiers(visibility)
            .addTypeVariables(typeVariables)
            .receiver(selfType)
            .addParameter("other", selfType)
            .returns(maskType)
            .addKdoc("Returns a mask with one bit set for each property that differs from [other].\n")
            .addStatement("var mask = %L", zero)
        // Unsigned arrays and `contentEquals` on them are marked experimental. The user's class
        // already opted in to declare such a property, so the generated functions opt in as well.
        val optIn = if (properties.any { property ->
                property.type.resolve().expanded().declaration.qualifiedName?.asString() in UNSIGNED_ARRAY_TYPES
            }
        ) {
            AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                .addMember("%T::class", ClassName("kotlin", "ExperimentalUnsignedTypes"))
                .build()
        } else {
            null
        }
        optIn?.let { diff.addAnnotation(it) }
        for ((i, property) in properties.withIndex()) {
            diff.beginControlFlow("if·(%L)", differs(property))
            diff.addStatement("mask·=·mask·or·(%L·shl·%L)", one, i)
            diff.endControlFlow()
        }
        diff.addStatement("return mask")

        val output = FileSpec.builder(packageName, "${flat}Diff")
            .addType(constants.build())
            .addFunction(diff.build())

        if (Modifier.DATA in declaration.modifiers) {
            val constructorParameters = declaration.primaryConstructor?.parameters
                ?.mapNotNull { it.name?.asString() }
                .orEmpty()
            val copyable = properties.filter { it.simpleName.asString() in constructorParameters }
            val apply = FunSpec.builder("applyDiff")
                .addModifiers(visibility)
                .addTypeVariables(typeVariables)
                .receiver(selfType)
                .addParameter("other", selfType)
                .addParameter("mask", maskType)
                .returns(selfType)
                .addKdoc("Returns a copy holding [other]'s value for each property set in [mask].\n")
                .addCode("return copy(\n")
            optIn?.let { apply.addAnnotation(it) }
            for (property in copyable) {
                val i = properties.indexOf(property)
                val name = property.simpleName.asString()
                apply.addCode(
                    "····%N·=·if·(mask·and·(%L·shl·%L)·!=·%L)·other.%N·else·this.%N,\n",
                    name, one, i, zero, name, name,
                )
            }
            apply.addCode(")\n")
            file?.let { apply.addOriginatingKSFile(it) }
            output.addFunction(apply.build())
        }

        output.build().writeTo(
            codeGenerator,
            aggregating = false,
            originatingKSFiles = listOfNotNull(file),
        )
    }

    /** Builds the expression deciding whether one property differs between `this` and `other`. */
    private fun differs(property: KSPropertyDeclaration): CodeBlock {
        val name = property.simpleName.asString()
        val type = property.type.resolve().expanded()
        val qualified = type.declaration.qualifiedName?.asString()
        return when {
            // `!=` on a statically typed Double is IEEE comparison, where NaN differs from
            // itself. A value that has not changed must not be reported as changed.
            (qualified == "kotlin.Double" || qualified == "kotlin.Float") && !type.isMarkedNullable ->
                CodeBlock.of("this.%N.compareTo(other.%N)·!=·0", name, name)
            // Kotlin applies the same IEEE rule to `Double?`. The value is already boxed, so this
            // compares with the box's `equals`, which treats NaN as equal to itself and allocates
            // nothing.
            qualified == "kotlin.Double" || qualified == "kotlin.Float" ->
                CodeBlock.of("!%T.equals(this.%N,·other.%N)", ClassName("java.util", "Objects"), name, name)
            // An `Array` can hold other arrays, and `contentEquals` would compare those by
            // identity. `contentDeepEquals` recurses into them and costs the same for flat arrays.
            qualified == "kotlin.Array" ->
                CodeBlock.of("!(this.%N·contentDeepEquals·other.%N)", name, name)
            // Unsigned arrays are value classes whose `equals` compares the backing array by
            // identity, so two arrays with equal contents would count as a change.
            qualified != null && (qualified in ARRAY_TYPES || qualified in UNSIGNED_ARRAY_TYPES) ->
                CodeBlock.of("!(this.%N·contentEquals·other.%N)", name, name)
            else -> CodeBlock.of("this.%N·!=·other.%N", name, name)
        }
    }

    private fun screamingCase(name: String): String = buildString {
        for ((i, c) in name.withIndex()) {
            if (c.isUpperCase() && i > 0 && !name[i - 1].isUpperCase()) append('_')
            append(c.uppercaseChar())
        }
    }

    // ---- Bitpack ----

    private enum class Kind(val typeWidth: Int, val description: String) {
        BOOLEAN(1, "Boolean"),
        BYTE(8, "Byte"),
        SHORT(16, "Short"),
        INT(32, "Int"),
        LONG(64, "Long"),
        CHAR(16, "Char"),
        UBYTE(8, "UByte"),
        USHORT(16, "UShort"),
        UINT(32, "UInt"),
        ULONG(64, "ULong"),
        FLOAT(32, "Float"),
        DOUBLE(64, "Double"),
        ENUM(32, "enum"),
        NESTED(64, "@Bitpack class"),
        ;

        /** Whole-number kinds. Their width must be stated, and values are range checked. */
        val integral: Boolean
            get() = when (this) {
                BYTE, SHORT, INT, LONG, CHAR, UBYTE, USHORT, UINT, ULONG -> true
                else -> false
            }

        /** Kinds with negative values, which may be stored as two's complement. */
        val signable: Boolean get() = this == BYTE || this == SHORT || this == INT || this == LONG
    }

    private class Field(
        val name: String,
        /** The type as declared, which may be nullable for an enum. */
        val typeName: TypeName,
        val kind: Kind,
        val bits: Int,
        val signed: Boolean,
        val offset: Int,
        /** A nullable enum stores null as 0 and each entry as its ordinal plus one. */
        val nullable: Boolean,
        /** For [Kind.NESTED], the generated value class of the nested type. */
        val packedType: ClassName?,
        /** For [Kind.NESTED], whether the nested type's generated code is internal. */
        val nestedInternal: Boolean,
        val node: KSNode,
    ) {
        val mask: String get() = if (bits == 64) "-1L" else "0x%XL".format((1L shl bits) - 1)
        val valueTypeName: TypeName get() = typeName.copy(nullable = false)
        val setterName: String get() = "with" + name.replaceFirstChar { it.uppercaseChar() }
        val packedGetterName: String get() = name + "Packed"
    }

    private fun kindOf(type: KSType): Kind? {
        val declaration = type.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS) {
            return Kind.ENUM
        }
        if (type.isMarkedNullable) return null
        if (declaration is KSClassDeclaration && declaration.hasAnnotation(BITPACK)) return Kind.NESTED
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> Kind.BOOLEAN
            "kotlin.Byte" -> Kind.BYTE
            "kotlin.Short" -> Kind.SHORT
            "kotlin.Int" -> Kind.INT
            "kotlin.Long" -> Kind.LONG
            "kotlin.Char" -> Kind.CHAR
            "kotlin.UByte" -> Kind.UBYTE
            "kotlin.UShort" -> Kind.USHORT
            "kotlin.UInt" -> Kind.UINT
            "kotlin.ULong" -> Kind.ULONG
            "kotlin.Float" -> Kind.FLOAT
            "kotlin.Double" -> Kind.DOUBLE
            else -> null
        }
    }

    private fun bitsNeeded(entries: Int): Int =
        if (entries <= 1) 1 else 32 - Integer.numberOfLeadingZeros(entries - 1)

    private fun KSClassDeclaration.qualifiedOrSimpleName(): String =
        qualifiedName?.asString() ?: simpleName.asString()

    /**
     * Works out the bit layout of a `@Bitpack` class. [path] holds the classes that contain this
     * one, outermost first, so that a class containing itself is reported instead of recursing
     * forever.
     */
    private fun layoutOf(
        declaration: KSClassDeclaration,
        path: List<KSClassDeclaration> = emptyList(),
    ): List<Field> {
        if (declaration.classKind != ClassKind.CLASS) {
            throw Rejected("@Bitpack only applies to classes", declaration)
        }
        if (declaration.typeParameters.isNotEmpty()) {
            throw Rejected("@Bitpack does not support generic classes", declaration)
        }
        val className = declaration.simpleName.asString()
        val constructor = declaration.primaryConstructor
            ?: throw Rejected("@Bitpack needs a primary constructor", declaration)
        val constructorVisibility = constructor.getVisibility()
        if (constructorVisibility == Visibility.PRIVATE || constructorVisibility == Visibility.PROTECTED) {
            throw Rejected(
                "@Bitpack needs a primary constructor that generated code can call, so that unpack() " +
                    "can rebuild the value, but the constructor of $className is " +
                    constructorVisibility.name.lowercase(),
                constructor,
            )
        }
        val fields = ArrayList<Field>()
        var offset = 0
        for (parameter in constructor.parameters) {
            val name = parameter.name?.asString()
                ?: throw Rejected("constructor parameters must be named", parameter)
            if (!parameter.isVal && !parameter.isVar) {
                throw Rejected(
                    "@Bitpack constructor parameter $name must be a property, so it can be read back",
                    parameter,
                )
            }
            val property = declaration.getDeclaredProperties().firstOrNull { it.simpleName.asString() == name }
            val propertyVisibility = property?.getVisibility()
            if (propertyVisibility == Visibility.PRIVATE || propertyVisibility == Visibility.PROTECTED) {
                throw Rejected(
                    "generated code cannot read the ${propertyVisibility.name.lowercase()} property " +
                        "$name of $className. @Bitpack properties must be public or internal",
                    parameter,
                )
            }
            val type = parameter.type.resolve().expanded()
            val kind = kindOf(type) ?: throw Rejected(
                "@Bitpack property $name has the unsupported type $type. Supported types are " +
                    "Boolean, Byte, Short, Int, Long, Char, UByte, UShort, UInt, ULong, Float, Double, " +
                    "enum classes, which may be nullable, and other @Bitpack classes from this module",
                parameter,
            )
            val annotation = parameter.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == BITS
            }
            val requested = annotation?.arguments?.firstOrNull { it.name?.asString() == "value" }?.value as? Int
            val signed = annotation?.arguments?.firstOrNull { it.name?.asString() == "signed" }?.value as? Boolean
                ?: false
            val nullable = kind == Kind.ENUM && type.isMarkedNullable

            val enumEntries = if (kind == Kind.ENUM) {
                (type.declaration as KSClassDeclaration).declarations
                    .count { it is KSClassDeclaration && it.classKind == ClassKind.ENUM_ENTRY }
            } else {
                0
            }
            // A nullable enum needs one more code than it has entries, for null.
            val enumCodes = if (nullable) enumEntries + 1 else enumEntries

            var packedType: ClassName? = null
            var nestedBits = 0
            var nestedInternal = false
            if (kind == Kind.NESTED) {
                val inner = type.declaration as KSClassDeclaration
                val chain = path + declaration
                val innerName = inner.qualifiedOrSimpleName()
                val start = chain.indexOfFirst { it.qualifiedOrSimpleName() == innerName }
                if (start >= 0) {
                    val loop = (chain.drop(start) + inner).joinToString(" -> ") { it.simpleName.asString() }
                    throw Cycle(
                        "@Bitpack property $name makes ${inner.simpleName.asString()} contain itself " +
                            "($loop), so it has no finite width",
                        parameter,
                    )
                }
                val innerFields = try {
                    nestedInternal = inner.generatedVisibility() == KModifier.INTERNAL
                    layoutOf(inner, chain)
                } catch (e: Cycle) {
                    // Reported at the outermost class's own property, where it can be fixed.
                    throw if (path.isEmpty()) Cycle(e.message.orEmpty(), parameter) else e
                } catch (e: Rejected) {
                    throw Rejected(
                        "@Bitpack property $name has the type ${inner.simpleName.asString()}, which " +
                            "cannot be packed: ${e.message}",
                        parameter,
                    )
                }
                nestedBits = innerFields.last().let { it.offset + it.bits }
                packedType = ClassName(inner.packageName.asString(), "Packed" + inner.flatName())
            }

            val bits = requested ?: when (kind) {
                Kind.BOOLEAN -> 1
                Kind.ENUM -> bitsNeeded(enumCodes)
                Kind.FLOAT, Kind.DOUBLE -> kind.typeWidth
                Kind.NESTED -> nestedBits
                else -> throw Rejected(
                    "@Bitpack property $name needs @Bits to say how wide it is",
                    parameter,
                )
            }
            if (bits < 1) throw Rejected("$name must be at least one bit wide", parameter)
            if (kind != Kind.ENUM && bits > kind.typeWidth) {
                throw Rejected("$name is $bits bits wide, but its type only has ${kind.typeWidth}", parameter)
            }
            if ((kind == Kind.FLOAT || kind == Kind.DOUBLE) && bits != kind.typeWidth) {
                throw Rejected(
                    "$name is a ${kind.description}, which is stored as all ${kind.typeWidth} of its " +
                        "IEEE 754 bits, but it was given $bits",
                    parameter,
                )
            }
            if (kind == Kind.ENUM && bits < bitsNeeded(enumCodes)) {
                val what = if (nullable) "$enumEntries enum entries plus null" else "$enumEntries enum entries"
                throw Rejected(
                    "$name has $what, which need at least ${bitsNeeded(enumCodes)} bits, but it was given $bits",
                    parameter,
                )
            }
            if (kind == Kind.NESTED && bits < nestedBits) {
                throw Rejected(
                    "$name holds a packed ${type.declaration.simpleName.asString()}, which needs " +
                        "$nestedBits bits, but it was given $bits",
                    parameter,
                )
            }
            if (signed && !kind.signable) {
                val reason = when (kind) {
                    Kind.FLOAT, Kind.DOUBLE -> "a ${kind.description} already keeps its sign in its IEEE 754 bits"
                    Kind.NESTED -> "it holds a packed @Bitpack value"
                    else -> "its type has no negative values"
                }
                throw Rejected("$name cannot be signed, because $reason", parameter)
            }
            fields.add(
                Field(
                    name = name,
                    typeName = parameter.type.toTypeName(),
                    kind = kind,
                    bits = bits,
                    signed = signed,
                    offset = offset,
                    nullable = nullable,
                    packedType = packedType,
                    nestedInternal = nestedInternal,
                    node = parameter,
                ),
            )
            offset += bits
            if (offset > 64) {
                throw Rejected(
                    "@Bitpack properties of $className need more than 64 bits",
                    parameter,
                )
            }
        }
        if (fields.isEmpty()) throw Rejected("@Bitpack needs at least one property", declaration)
        return fields
    }

    /** The field's bits, moved down to bit 0, as a Long. */
    private fun extract(field: Field): CodeBlock = if (field.signed) {
        // Moving the field to the top, then back down arithmetically, extends its sign.
        CodeBlock.of("((raw·shl·%L)·shr·%L)", 64 - field.offset - field.bits, 64 - field.bits)
    } else {
        CodeBlock.of("((raw·ushr·%L)·and·%L)", field.offset, field.mask)
    }

    /** The value of a field, read out of `raw`. */
    private fun readExpression(field: Field): CodeBlock {
        val shifted = extract(field)
        return when (field.kind) {
            Kind.BOOLEAN -> CodeBlock.of("%L·!=·0L", shifted)
            Kind.BYTE -> CodeBlock.of("%L.toByte()", shifted)
            Kind.SHORT -> CodeBlock.of("%L.toShort()", shifted)
            Kind.INT -> CodeBlock.of("%L.toInt()", shifted)
            Kind.LONG -> shifted
            Kind.CHAR -> CodeBlock.of("%T(%L.toInt())", CHAR, shifted)
            Kind.UBYTE -> CodeBlock.of("%L.toUByte()", shifted)
            Kind.USHORT -> CodeBlock.of("%L.toUShort()", shifted)
            Kind.UINT -> CodeBlock.of("%L.toUInt()", shifted)
            Kind.ULONG -> CodeBlock.of("%L.toULong()", shifted)
            Kind.FLOAT -> CodeBlock.of("%T.fromBits(%L.toInt())", FLOAT, shifted)
            Kind.DOUBLE -> CodeBlock.of("%T.fromBits(%L)", DOUBLE, shifted)
            Kind.ENUM -> if (field.nullable) {
                CodeBlock.of(
                    "%L.toInt().let·{·if·(it·==·0)·null·else·%T.entries[it·-·1]·}",
                    shifted,
                    field.valueTypeName,
                )
            } else {
                CodeBlock.of("%T.entries[%L.toInt()]", field.valueTypeName, shifted)
            }
            Kind.NESTED -> CodeBlock.of("%T(%L).unpack()", field.packedType!!, shifted)
        }
    }

    /** The value widened to a Long. [value] is an expression with no spaces in it. */
    private fun widen(field: Field, value: String): CodeBlock = when (field.kind) {
        Kind.BOOLEAN -> CodeBlock.of("(if·(%L)·1L·else·0L)", value)
        Kind.LONG -> CodeBlock.of("%L", value)
        Kind.CHAR -> CodeBlock.of("%L.code.toLong()", value)
        // Unsigned conversions to Long zero-extend, and ULong keeps its 64 bits as they are.
        Kind.UBYTE, Kind.USHORT, Kind.UINT, Kind.ULONG -> CodeBlock.of("%L.toLong()", value)
        // The raw bits keep NaN payloads, so a value comes back exactly as it went in.
        Kind.FLOAT -> CodeBlock.of("%L.toRawBits().toLong()", value)
        Kind.DOUBLE -> CodeBlock.of("%L.toRawBits()", value)
        Kind.ENUM -> if (field.nullable) {
            CodeBlock.of("(when·(val·v·=·%L)·{·null·->·0L;·else·->·v.ordinal·+·1L·})", value)
        } else {
            CodeBlock.of("%L.ordinal.toLong()", value)
        }
        // pack() checks the nested value against its own field widths.
        Kind.NESTED -> CodeBlock.of("%L.pack().raw", value)
        else -> CodeBlock.of("%L.toLong()", value)
    }

    /** Statements that reject a value the field cannot represent. Empty if all values fit. */
    private fun rangeCheck(field: Field, value: String): CodeBlock {
        val builder = CodeBlock.builder()
        if (!field.kind.integral || field.bits >= field.kind.typeWidth) {
            return builder.build()
        }
        val low: Long
        val high: Long
        if (field.signed) {
            low = -(1L shl (field.bits - 1))
            high = (1L shl (field.bits - 1)) - 1
        } else {
            low = 0
            high = (1L shl field.bits) - 1
        }
        // For a ULong, values of 2^63 and above widen to negative Longs and fail `< 0`.
        val wide = widen(field, value)
        builder.beginControlFlow("if·(%L·<·%LL·||·%L·>·%LL)", wide, low, wide, high)
        builder.addStatement(
            "throw·%T(%P)",
            ClassName("kotlin", "IllegalArgumentException"),
            "${field.name} must be in $low..$high, but was \${$value}",
        )
        builder.endControlFlow()
        return builder.build()
    }

    /**
     * Rejects properties whose generated members would clash with each other or with `raw`, so
     * the mistake is reported at the property instead of as a redeclaration in generated code.
     */
    private fun checkMemberNames(packedName: String, fields: List<Field>) {
        val properties = HashMap<String, String>()
        properties["raw"] = "the property that holds the packed bits"
        val functions = HashMap<String, String>()
        functions["unpack"] = "the function that rebuilds the value"
        fun claim(table: HashMap<String, String>, member: String, field: Field, what: String) {
            val previous = table.put(member, "$what of property ${field.name}")
            if (previous != null) {
                throw Rejected(
                    "@Bitpack property ${field.name} would generate $packedName.$member, which clashes " +
                        "with $previous. Rename the property",
                    field.node,
                )
            }
        }
        for (field in fields) {
            claim(properties, field.name, field, "the getter")
            claim(functions, field.setterName, field, "the setter")
            if (field.kind == Kind.NESTED) {
                claim(properties, field.packedGetterName, field, "the packed getter")
            }
        }
    }

    private fun generateBitpack(declaration: KSClassDeclaration) {
        val fields = layoutOf(declaration)
        val visibility = declaration.generatedVisibility()
        val className = declaration.toClassName()
        val flat = declaration.flatName()
        val packageName = declaration.packageName.asString()
        val packedName = "Packed$flat"
        val packed = ClassName(packageName, packedName)
        val file = declaration.containingFile
        checkMemberNames(packedName, fields)
        if (visibility == KModifier.PUBLIC) {
            fields.firstOrNull { it.nestedInternal }?.let { field ->
                throw Rejected(
                    "${declaration.simpleName.asString()} is public, but its @Bitpack property " +
                        "${field.name} has an internal type, which public generated code cannot expose. " +
                        "Make both public or both internal",
                    field.node,
                )
            }
        }

        val type = TypeSpec.classBuilder(packedName)
            .addModifiers(visibility, KModifier.VALUE)
            .addAnnotation(JvmInline::class)
            .addKdoc("A [%T] packed into one `Long`, with no object behind it at run time.\n", className)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("raw", LONG).build())
            .addProperty(PropertySpec.builder("raw", LONG).initializer("raw").build())
        file?.let { type.addOriginatingKSFile(it) }

        for (field in fields) {
            type.addProperty(
                PropertySpec.builder(field.name, field.typeName)
                    .getter(FunSpec.getterBuilder().addCode("return %L\n", readExpression(field)).build())
                    .build(),
            )
            val setter = FunSpec.builder(field.setterName)
                .addParameter("value", field.typeName)
                .returns(packed)
                .addCode(rangeCheck(field, "value"))
            setter.addStatement(
                "return·%T((raw·and·(%L·shl·%L).inv())·or·((%L·and·%L)·shl·%L))",
                packed,
                field.mask,
                field.offset,
                widen(field, "value"),
                field.mask,
                field.offset,
            )
            type.addFunction(setter.build())

            if (field.kind == Kind.NESTED) {
                val inner = field.packedType!!
                // Reading the nested value as its packed form allocates nothing.
                type.addProperty(
                    PropertySpec.builder(field.packedGetterName, inner)
                        .addKdoc("[%N] in its packed form, without building an object.\n", field.name)
                        .getter(
                            FunSpec.getterBuilder()
                                .addCode("return %T(%L)\n", inner, extract(field))
                                .build(),
                        )
                        .build(),
                )
                type.addFunction(
                    FunSpec.builder(field.setterName)
                        .addParameter("value", inner)
                        .returns(packed)
                        .beginControlFlow("if·(value.raw·and·(%L).inv()·!=·0L)", field.mask)
                        .addStatement(
                            "throw·%T(%P)",
                            ClassName("kotlin", "IllegalArgumentException"),
                            "${field.name} is ${field.bits} bits wide, but the packed value " +
                                "\${value.raw} sets bits beyond it",
                        )
                        .endControlFlow()
                        .addStatement(
                            "return·%T((raw·and·(%L·shl·%L).inv())·or·(value.raw·shl·%L))",
                            packed,
                            field.mask,
                            field.offset,
                            field.offset,
                        )
                        .build(),
                )
            }
        }

        val unpack = FunSpec.builder("unpack")
            .returns(className)
            .addStatement(
                "return %T(%L)",
                className,
                fields.joinToString(", ") { "${it.name} = ${it.name}" },
            )
        type.addFunction(unpack.build())

        val pack = FunSpec.builder("pack")
            .addModifiers(visibility)
            .receiver(className)
            .returns(packed)
            .addKdoc("Packs this value. Throws if a property does not fit in its declared width.\n")
        for (field in fields) pack.addCode(rangeCheck(field, "this.${field.name}"))
        val parts = fields.map { field ->
            CodeBlock.of(
                "((%L·and·%L)·shl·%L)",
                widen(field, "this.${field.name}"),
                field.mask,
                field.offset,
            )
        }.joinToCode("·or·")
        pack.addStatement("return·%T(%L)", packed, parts)

        val totalBits = fields.last().offset + fields.last().bits
        val widthConstant = PropertySpec.builder("${packedName}_BITS", INT)
            .addModifiers(visibility, KModifier.CONST)
            .addKdoc("The number of bits [%T] actually uses.\n", packed)
            .initializer("%L", totalBits)
            .build()

        FileSpec.builder(packageName, packedName)
            .addType(type.build())
            .addFunction(pack.build())
            .addProperty(widthConstant)
            .build()
            .writeTo(
                codeGenerator,
                aggregating = false,
                originatingKSFiles = listOfNotNull(file),
            )
    }

    private companion object {
        val ARRAY_TYPES = setOf(
            "kotlin.Array",
            "kotlin.ByteArray",
            "kotlin.ShortArray",
            "kotlin.IntArray",
            "kotlin.LongArray",
            "kotlin.FloatArray",
            "kotlin.DoubleArray",
            "kotlin.CharArray",
            "kotlin.BooleanArray",
        )
        val UNSIGNED_ARRAY_TYPES = setOf(
            "kotlin.UByteArray",
            "kotlin.UShortArray",
            "kotlin.UIntArray",
            "kotlin.ULongArray",
        )
    }
}
