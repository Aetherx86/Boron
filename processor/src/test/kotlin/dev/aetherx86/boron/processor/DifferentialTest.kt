package dev.aetherx86.boron.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generates random annotated classes, compiles them with the real processor and compiler, then
 * runs the generated code and compares every result with a reference model written here, which
 * shares no code with the generator.
 *
 * Each class gets a `check` function, emitted as source, that holds the expected values as
 * literals worked out by the model. It returns null on success or a description of the first
 * mismatch.
 */
@OptIn(ExperimentalCompilerApi::class)
class DifferentialTest {
    private fun compileAndRun(source: String, checks: Int): List<String> {
        val result = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Input.kt",
                    "@file:OptIn(ExperimentalUnsignedTypes::class)\npackage test\nimport dev.aetherx86.boron.*\n$source",
                ),
            )
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
            configureKsp { symbolProcessorProviders += BoronProcessorProvider() }
        }.compile()
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            result.messages.lines().filter { it.startsWith("e:") }.joinToString("\n"),
        )
        val holder = result.classLoader.loadClass("test.InputKt")
        return (0 until checks).mapNotNull { holder.getMethod("check$it").invoke(null) as String? }
    }

    // ---- Bitpack ----

    private enum class Type(val width: Int, val integral: Boolean, val signable: Boolean, val kotlin: String) {
        BOOLEAN(1, false, false, "Boolean"),
        BYTE(8, true, true, "Byte"),
        SHORT(16, true, true, "Short"),
        INT(32, true, true, "Int"),
        LONG(64, true, true, "Long"),
        CHAR(16, true, false, "Char"),
        UBYTE(8, true, false, "UByte"),
        USHORT(16, true, false, "UShort"),
        UINT(32, true, false, "UInt"),
        ULONG(64, true, false, "ULong"),
        FLOAT(32, false, false, "Float"),
        DOUBLE(64, false, false, "Double"),
        ENUM(0, false, false, ""),
        NULLABLE_ENUM(0, false, false, ""),
    }

    private class Spec(
        val name: String,
        val type: Type,
        val bits: Int,
        val signed: Boolean,
        val explicitBits: Boolean,
        val enumName: String,
        val enumSize: Int,
        val offset: Int,
    ) {
        val mask: Long get() = if (bits == 64) -1L else (1L shl bits) - 1
        val kotlinType: String
            get() = when (type) {
                Type.ENUM -> enumName
                Type.NULLABLE_ENUM -> "$enumName?"
                else -> type.kotlin
            }
    }

    private fun bitsFor(codes: Int): Int = if (codes <= 1) 1 else 64 - java.lang.Long.numberOfLeadingZeros(codes - 1L)

    /**
     * A value is held as a Long: the number itself for whole numbers (unsigned ones as their bit
     * pattern), raw IEEE bits for floats, the ordinal for enums, and -1 for a null enum.
     */
    private fun randomValue(spec: Spec, random: Random): Long = when (spec.type) {
        Type.BOOLEAN -> random.nextInt(2).toLong()
        Type.FLOAT -> {
            var bits = random.nextInt()
            if (java.lang.Float.isNaN(java.lang.Float.intBitsToFloat(bits))) bits = bits or 0x0040_0000
            bits.toLong() and 0xFFFF_FFFFL
        }
        Type.DOUBLE -> {
            var bits = random.nextLong()
            if (java.lang.Double.isNaN(java.lang.Double.longBitsToDouble(bits))) bits = bits or 0x0008_0000_0000_0000L
            bits
        }
        Type.ENUM -> random.nextInt(spec.enumSize).toLong()
        Type.NULLABLE_ENUM -> random.nextInt(spec.enumSize + 1).toLong() - 1
        else -> {
            // Favor the edges of the range, where shifts and sign extension go wrong.
            val raw = when (random.nextInt(4)) {
                0 -> 0L
                1 -> -1L
                2 -> if (spec.signed) 1L shl (spec.bits - 1) else -1L
                else -> random.nextLong()
            }
            if (spec.signed) (raw shl (64 - spec.bits)) shr (64 - spec.bits) else raw and spec.mask
        }
    }

    /** The bits the model expects a field to occupy, before shifting to its offset. */
    private fun code(spec: Spec, value: Long): Long = when (spec.type) {
        Type.NULLABLE_ENUM -> value + 1
        else -> value and spec.mask
    }

    private fun longLiteral(value: Long): String =
        if (value == Long.MIN_VALUE) "Long.MIN_VALUE" else "(${value}L)"

    private fun literal(spec: Spec, value: Long): String = when (spec.type) {
        Type.BOOLEAN -> if (value != 0L) "true" else "false"
        Type.BYTE -> "${longLiteral(value)}.toByte()"
        Type.SHORT -> "${longLiteral(value)}.toShort()"
        Type.INT -> "${longLiteral(value)}.toInt()"
        Type.LONG -> longLiteral(value)
        Type.CHAR -> "Char($value)"
        Type.UBYTE -> "${longLiteral(value)}.toUByte()"
        Type.USHORT -> "${longLiteral(value)}.toUShort()"
        Type.UINT -> "${longLiteral(value)}.toUInt()"
        Type.ULONG -> "${longLiteral(value)}.toULong()"
        Type.FLOAT -> "Float.fromBits(${longLiteral(value)}.toInt())"
        Type.DOUBLE -> "Double.fromBits(${longLiteral(value)})"
        Type.ENUM -> "${spec.enumName}.V$value"
        Type.NULLABLE_ENUM -> if (value < 0) "null" else "${spec.enumName}.V$value"
    }

    /** An expression that is true when [actual] holds a different value than [value]. */
    private fun differs(spec: Spec, actual: String, value: Long): String = when (spec.type) {
        Type.FLOAT, Type.DOUBLE -> "$actual.toRawBits() != ${literal(spec.copyAs(Type.LONG), value)}" +
            if (spec.type == Type.FLOAT) ".toInt()" else ""
        else -> "$actual != ${literal(spec, value)}"
    }

    private fun Spec.copyAs(type: Type) = Spec(name, type, bits, signed, explicitBits, enumName, enumSize, offset)

    private fun randomLayout(random: Random, index: Int, enums: StringBuilder): List<Spec> {
        val specs = ArrayList<Spec>()
        var offset = 0
        val count = 1 + random.nextInt(10)
        var attempts = 0
        while (specs.size < count && attempts++ < 50) {
            val type = Type.entries[random.nextInt(Type.entries.size)]
            var enumName = ""
            var enumSize = 0
            var signed = false
            var explicit = true
            val bits = when (type) {
                Type.BOOLEAN -> {
                    explicit = random.nextBoolean()
                    1
                }
                Type.FLOAT, Type.DOUBLE -> {
                    explicit = random.nextBoolean()
                    type.width
                }
                Type.ENUM, Type.NULLABLE_ENUM -> {
                    enumSize = 1 + random.nextInt(40)
                    enumName = "E${index}_${specs.size}"
                    val needed = bitsFor(if (type == Type.NULLABLE_ENUM) enumSize + 1 else enumSize)
                    explicit = random.nextInt(3) == 0
                    if (explicit) needed + random.nextInt(3) else needed
                }
                else -> {
                    signed = type.signable && random.nextBoolean()
                    1 + random.nextInt(type.width)
                }
            }
            if (offset + bits > 64) continue
            if (enumName.isNotEmpty()) {
                enums.append("enum class $enumName { ")
                enums.append((0 until enumSize).joinToString(", ") { "V$it" })
                enums.append(" }\n")
            }
            specs += Spec("f${specs.size}", type, bits, signed, explicit, enumName, enumSize, offset)
            offset += bits
        }
        return specs
    }

    private fun emitBitpackCheck(index: Int, specs: List<Spec>, random: Random, out: StringBuilder) {
        val name = "L$index"
        out.append("@Bitpack data class $name(")
        out.append(
            specs.joinToString(", ") { spec ->
                val annotation = when {
                    !spec.explicitBits -> ""
                    spec.signed -> "@Bits(${spec.bits}, signed = true) "
                    else -> "@Bits(${spec.bits}) "
                }
                "$annotation${if (random.nextBoolean()) "val" else "var"} ${spec.name}: ${spec.kotlinType}"
            },
        )
        out.append(")\n")
        out.append("fun check$index(): String? {\n")
        out.append("    if (Packed${name}_BITS != ${specs.last().offset + specs.last().bits}) return \"$name width\"\n")
        repeat(6) { sample ->
            val values = specs.map { randomValue(it, random) }
            var raw = 0L
            for ((spec, value) in specs.zip(values)) raw = raw or (code(spec, value) shl spec.offset)
            val args = specs.zip(values).joinToString(", ") { (spec, value) -> "${spec.name} = ${literal(spec, value)}" }
            val where = "$name sample $sample"
            out.append("    run {\n")
            out.append("        val v = $name($args)\n")
            out.append("        val p = v.pack()\n")
            out.append("        if (p.raw != ${longLiteral(raw)}) return \"$where: raw \${p.raw} != $raw\"\n")
            out.append("        if (p.unpack() != v) return \"$where: unpack \${p.unpack()} != \$v\"\n")
            out.append("        val q = Packed$name(${longLiteral(raw)})\n")
            for ((spec, value) in specs.zip(values)) {
                out.append(
                    "        if (${differs(spec, "q.${spec.name}", value)}) " +
                        "return \"$where: ${spec.name} read \${q.${spec.name}}\"\n",
                )
                val replacement = randomValue(spec, random)
                val field = spec.mask shl spec.offset
                val expected = (raw and field.inv()) or (code(spec, replacement) shl spec.offset)
                val setter = "with" + spec.name.replaceFirstChar { it.uppercaseChar() }
                out.append(
                    "        if (p.$setter(${literal(spec, replacement)}).raw != ${longLiteral(expected)}) " +
                        "return \"$where: $setter changed other bits\"\n",
                )
                // Whole numbers narrower than their type must refuse what does not fit.
                if (spec.type.integral && spec.bits < spec.type.width) {
                    val outside = if (spec.signed) {
                        if (random.nextBoolean()) 1L shl (spec.bits - 1) else -(1L shl (spec.bits - 1)) - 1
                    } else {
                        1L shl spec.bits
                    }
                    val bad = literal(spec, outside)
                    out.append(
                        "        try { p.$setter($bad); return \"$where: $setter accepted $outside\" } " +
                            "catch (e: IllegalArgumentException) {}\n",
                    )
                    out.append(
                        "        try { v.copy(${spec.name} = $bad).pack(); return \"$where: pack accepted $outside\" } " +
                            "catch (e: IllegalArgumentException) {}\n",
                    )
                }
            }
            out.append("    }\n")
        }
        out.append("    return null\n}\n")
    }

    @Test
    fun randomBitpackLayoutsMatchTheReferenceModel() {
        val random = Random(0xB0B0)
        val layouts = 48
        val enums = StringBuilder()
        val body = StringBuilder()
        val seen = HashSet<Type>()
        for (index in 0 until layouts) {
            val specs = randomLayout(random, index, enums)
            specs.mapTo(seen) { it.type }
            emitBitpackCheck(index, specs, random, body)
        }
        assertEquals(Type.entries.toSet(), seen, "every type should appear in some layout")
        val failures = compileAndRun("$enums$body", layouts)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- FastDiff ----

    /**
     * A property type and a pool of values for it. Values with the same class are equal under
     * the rules `diffMask` promises: NaN equals NaN, `0.0` and `-0.0` differ, and arrays compare by
     * contents at every depth.
     */
    private class Pool(val type: String, val values: List<Pair<String, Int>>)

    private val pools = listOf(
        Pool("Int", listOf("1" to 0, "2" to 1, "-1" to 2)),
        Pool("Long", listOf("0L" to 0, "Long.MIN_VALUE" to 1)),
        Pool("Boolean", listOf("true" to 0, "false" to 1)),
        Pool("Char", listOf("'a'" to 0, "'b'" to 1)),
        Pool("String?", listOf("null" to 0, "\"a\"" to 1, "\"b\"" to 2, "String(charArrayOf('a'))" to 1)),
        Pool(
            "Double",
            listOf(
                "Double.NaN" to 0, "Double.fromBits(0x7FF8000000000001L)" to 0,
                "0.0" to 1, "-0.0" to 2, "1.5" to 3,
            ),
        ),
        Pool("Celsius", listOf("Double.NaN" to 0, "0.0" to 1, "-0.0" to 2)),
        Pool("Double?", listOf("null" to 0, "Double.NaN" to 1, "0.0" to 2, "-0.0" to 3)),
        Pool("Float", listOf("Float.NaN" to 0, "0f" to 1, "-0f" to 2, "Float.fromBits(0x7FC00001)" to 0)),
        Pool("Float?", listOf("null" to 0, "Float.NaN" to 1, "0f" to 2)),
        Pool("IntArray", listOf("intArrayOf(1, 2)" to 0, "intArrayOf()" to 1, "intArrayOf(2, 1)" to 2)),
        Pool("Samples", listOf("intArrayOf(1)" to 0, "intArrayOf()" to 1)),
        Pool("IntArray?", listOf("null" to 0, "intArrayOf(1)" to 1, "intArrayOf()" to 2)),
        Pool(
            "Array<IntArray>",
            listOf(
                "arrayOf(intArrayOf(1))" to 0, "arrayOf(intArrayOf(2))" to 1,
                "arrayOf<IntArray>()" to 2, "arrayOf(intArrayOf(1), intArrayOf())" to 3,
            ),
        ),
        Pool("Array<String?>", listOf("arrayOf<String?>(null)" to 0, "arrayOf<String?>(\"a\")" to 1)),
        Pool("UIntArray", listOf("uintArrayOf(1u)" to 0, "uintArrayOf()" to 1, "uintArrayOf(UInt.MAX_VALUE)" to 2)),
        Pool("List<Int>", listOf("listOf(1)" to 0, "listOf(1, 2)" to 1, "mutableListOf(1)" to 0)),
    )

    @Test
    fun randomDiffsMatchTheReferenceModel() {
        val random = Random(0xD1FF)
        val classes = 40
        val source = StringBuilder("typealias Celsius = Double\ntypealias Samples = IntArray\n")
        for (index in 0 until classes) {
            // Up to 40 properties, so both Int and Long masks are exercised.
            val count = 1 + random.nextInt(if (index % 4 == 0) 40 else 12)
            val props = List(count) { pools[random.nextInt(pools.size)] }
            val longMask = count > 32
            val suffix = if (longMask) "L" else ""
            val name = "D$index"
            source.append("@FastDiff data class $name(")
            source.append(props.withIndex().joinToString(", ") { (i, pool) -> "val p$i: ${pool.type}" })
            source.append(")\nfun check$index(): String? {\n")
            repeat(12) { sample ->
                val left = props.map { it.values[random.nextInt(it.values.size)] }
                // Often reuse the left value's class, so that "unchanged" is common.
                val right = props.zip(left).map { (pool, l) ->
                    if (random.nextInt(3) == 0) pool.values[random.nextInt(pool.values.size)] else l
                }
                var expected = 0L
                for (i in props.indices) if (left[i].second != right[i].second) expected = expected or (1L shl i)
                val expectedLiteral = if (longMask) longLiteral(expected) else "${longLiteral(expected.toInt().toLong())}.toInt()"
                val where = "$name sample $sample"
                source.append("    run {\n")
                source.append("        val a = $name(${left.joinToString(", ") { it.first }})\n")
                source.append("        val b = $name(${right.joinToString(", ") { it.first }})\n")
                source.append("        val m = a.diffMask(b)\n")
                source.append("        if (m != $expectedLiteral) return \"$where: mask \$m != $expected\"\n")
                source.append("        if (b.diffMask(a) != m) return \"$where: not symmetric\"\n")
                source.append("        if (a.diffMask(a) != 0$suffix) return \"$where: differs from itself\"\n")
                source.append("        if (a.applyDiff(b, m).diffMask(b) != 0$suffix) return \"$where: applyDiff incomplete\"\n")
                source.append("        if (a.applyDiff(b, 0$suffix).diffMask(a) != 0$suffix) return \"$where: applyDiff(0) changed\"\n")
                source.append("        if (${name}Diff.names(m).size != m.countOneBits()) return \"$where: names\"\n")
                source.append("    }\n")
            }
            source.append("    return null\n}\n")
        }
        val failures = compileAndRun(source.toString(), classes)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
