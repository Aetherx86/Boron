package dev.aetherx86.boron.processor

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
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
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
    private class Rejected(message: String, val node: KSNode) : Exception(message)

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

    private fun KSPropertyDeclaration.hasAnnotation(name: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == name }

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
        val flat = declaration.flatName()
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
        val type = property.type.resolve()
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
            qualified != null && qualified in ARRAY_TYPES ->
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

    private enum class Kind(val typeWidth: Int) {
        BOOLEAN(1), BYTE(8), SHORT(16), INT(32), LONG(64), ENUM(32)
    }

    private class Field(
        val name: String,
        val typeName: TypeName,
        val kind: Kind,
        val bits: Int,
        val signed: Boolean,
        val offset: Int,
        val enumEntries: Int,
    ) {
        val mask: String get() = if (bits == 64) "-1L" else "0x%XL".format((1L shl bits) - 1)
    }

    private fun kindOf(type: KSType): Kind? {
        if (type.isMarkedNullable) return null
        val declaration = type.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS) {
            return Kind.ENUM
        }
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> Kind.BOOLEAN
            "kotlin.Byte" -> Kind.BYTE
            "kotlin.Short" -> Kind.SHORT
            "kotlin.Int" -> Kind.INT
            "kotlin.Long" -> Kind.LONG
            else -> null
        }
    }

    private fun bitsNeeded(entries: Int): Int =
        if (entries <= 1) 1 else 32 - Integer.numberOfLeadingZeros(entries - 1)

    private fun readFields(declaration: KSClassDeclaration): List<Field> {
        val constructor = declaration.primaryConstructor
            ?: throw Rejected("@Bitpack needs a primary constructor", declaration)
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
            val type = parameter.type.resolve()
            val kind = kindOf(type) ?: throw Rejected(
                "@Bitpack property $name has the unsupported type $type. Supported types are " +
                    "Boolean, Byte, Short, Int, Long and enum classes, none of them nullable",
                parameter,
            )
            val annotation = parameter.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == BITS
            }
            val requested = annotation?.arguments?.firstOrNull { it.name?.asString() == "value" }?.value as? Int
            val signed = annotation?.arguments?.firstOrNull { it.name?.asString() == "signed" }?.value as? Boolean
                ?: false

            val enumEntries = if (kind == Kind.ENUM) {
                (type.declaration as KSClassDeclaration).declarations
                    .count { it is KSClassDeclaration && it.classKind == ClassKind.ENUM_ENTRY }
            } else {
                0
            }
            val bits = requested ?: when (kind) {
                Kind.BOOLEAN -> 1
                Kind.ENUM -> bitsNeeded(enumEntries)
                else -> throw Rejected(
                    "@Bitpack property $name needs @Bits to say how wide it is",
                    parameter,
                )
            }
            if (bits < 1) throw Rejected("$name must be at least one bit wide", parameter)
            if (kind != Kind.ENUM && bits > kind.typeWidth) {
                throw Rejected("$name is $bits bits wide, but its type only has ${kind.typeWidth}", parameter)
            }
            if (kind == Kind.ENUM && bits < bitsNeeded(enumEntries)) {
                throw Rejected(
                    "$name has $enumEntries enum entries, which need at least " +
                        "${bitsNeeded(enumEntries)} bits, but it was given $bits",
                    parameter,
                )
            }
            if (signed && (kind == Kind.BOOLEAN || kind == Kind.ENUM)) {
                throw Rejected("$name cannot be signed, because its type has no negative values", parameter)
            }
            fields.add(
                Field(name, parameter.type.toTypeName(), kind, bits, signed, offset, enumEntries),
            )
            offset += bits
            if (offset > 64) {
                throw Rejected(
                    "@Bitpack properties of ${declaration.simpleName.asString()} need more than 64 bits",
                    parameter,
                )
            }
        }
        if (fields.isEmpty()) throw Rejected("@Bitpack needs at least one property", declaration)
        return fields
    }

    /** The value of a field, read out of `raw`. */
    private fun readExpression(field: Field): CodeBlock {
        val shifted = if (field.signed) {
            // Moving the field to the top, then back down arithmetically, extends its sign.
            CodeBlock.of("((raw·shl·%L)·shr·%L)", 64 - field.offset - field.bits, 64 - field.bits)
        } else {
            CodeBlock.of("((raw·ushr·%L)·and·%L)", field.offset, field.mask)
        }
        return when (field.kind) {
            Kind.BOOLEAN -> CodeBlock.of("%L·!=·0L", shifted)
            Kind.BYTE -> CodeBlock.of("%L.toByte()", shifted)
            Kind.SHORT -> CodeBlock.of("%L.toShort()", shifted)
            Kind.INT -> CodeBlock.of("%L.toInt()", shifted)
            Kind.LONG -> shifted
            Kind.ENUM -> CodeBlock.of("%T.entries[%L.toInt()]", field.typeName, shifted)
        }
    }

    /** The value widened to a Long. [value] is an expression with no spaces in it. */
    private fun widen(field: Field, value: String): CodeBlock = when (field.kind) {
        Kind.BOOLEAN -> CodeBlock.of("(if·(%L)·1L·else·0L)", value)
        Kind.LONG -> CodeBlock.of("%L", value)
        Kind.ENUM -> CodeBlock.of("%L.ordinal.toLong()", value)
        else -> CodeBlock.of("%L.toLong()", value)
    }

    /** Statements that reject a value the field cannot represent. Empty if all values fit. */
    private fun rangeCheck(field: Field, value: String): CodeBlock {
        val builder = CodeBlock.builder()
        if (field.kind == Kind.BOOLEAN || field.kind == Kind.ENUM || field.bits >= field.kind.typeWidth) {
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

    private fun generateBitpack(declaration: KSClassDeclaration) {
        if (declaration.classKind != ClassKind.CLASS) {
            throw Rejected("@Bitpack only applies to classes", declaration)
        }
        if (declaration.typeParameters.isNotEmpty()) {
            throw Rejected("@Bitpack does not support generic classes", declaration)
        }
        val visibility = declaration.generatedVisibility()
        val fields = readFields(declaration)
        val className = declaration.toClassName()
        val flat = declaration.flatName()
        val packageName = declaration.packageName.asString()
        val packedName = "Packed$flat"
        val packed = ClassName(packageName, packedName)
        val file = declaration.containingFile

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
            val setter = FunSpec.builder("with" + field.name.replaceFirstChar { it.uppercaseChar() })
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
    }
}
