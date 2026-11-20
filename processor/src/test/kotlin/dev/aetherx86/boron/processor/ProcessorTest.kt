package dev.aetherx86.boron.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the real processor through the real compiler on small programs. */
@OptIn(ExperimentalCompilerApi::class)
class ProcessorTest {
    private fun compile(
        source: String,
        provider: SymbolProcessorProvider = BoronProcessorProvider(),
    ): JvmCompilationResult = KotlinCompilation().apply {
        sources = listOf(
            SourceFile.kotlin(
                "Input.kt",
                "package test\nimport dev.aetherx86.boron.*\n$source",
            ),
        )
        inheritClassPath = true
        messageOutputStream = OutputStream.nullOutputStream()
        configureKsp {
            symbolProcessorProviders += provider
        }
    }.compile()

    private fun assertRejected(source: String, vararg needles: String) {
        val result = compile(source)
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "expected this to be rejected:\n$source\n${result.messages}",
        )
        for (needle in needles) {
            assertTrue(needle in result.messages, "expected '$needle' in:\n${result.messages}")
        }
    }

    private fun assertAccepted(source: String) {
        val result = compile(source)
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "expected this to compile:\n$source\n${result.messages}",
        )
    }

    @Test
    fun validInputCompiles() {
        assertAccepted("@FastDiff data class A(val x: Int, val y: String)")
        assertAccepted("@Bitpack data class B(@Bits(3) val x: Int, val flag: Boolean)")
    }

    @Test
    fun fastDiffRejectsWhatItCannotCompare() {
        assertRejected("@FastDiff class Empty", "at least one property")
        assertRejected("@FastDiff data class Ignored(@DiffIgnore val x: Int)", "at least one property")
        assertRejected("@FastDiff interface I { val x: Int }", "only applies to classes")
        assertRejected("@FastDiff object O { val x = 1 }", "only applies to classes")
        assertRejected("@FastDiff enum class E { A }", "only applies to classes")
        assertRejected("@FastDiff class P(private val hidden: Int)", "private property hidden", "@DiffIgnore")
        assertAccepted("@FastDiff class P(@DiffIgnore private val hidden: Int, val shown: Int)")
    }

    @Test
    fun fastDiffRejectsPrivateClasses() {
        assertRejected("@FastDiff private class Hidden(val x: Int)", "Hidden", "public or internal")
        assertRejected(
            "class Outer { @FastDiff private class Inner(val x: Int) }",
            "Inner",
            "public or internal",
        )
    }

    @Test
    fun fastDiffEnforcesTheMaskWidth() {
        val properties = { n: Int -> (0 until n).joinToString(", ") { "val p$it: Int" } }
        assertAccepted("@FastDiff data class Max(${properties(64)})")
        assertRejected("@FastDiff data class TooMany(${properties(65)})", "at most 64", "65")
    }

    @Test
    fun bitpackRejectsUnsupportedTypes() {
        assertRejected("@Bitpack data class S(@Bits(8) val text: String)", "text", "unsupported type")
        assertRejected("@Bitpack data class N(@Bits(8) val x: Int?)", "x", "unsupported type")
        assertRejected("@Bitpack data class L(@Bits(8) val x: List<Int>)", "x", "unsupported type")
        assertRejected("@Bitpack data class F(@Bits(32) val x: Float?)", "x", "unsupported type")
    }

    @Test
    fun bitpackRequiresWidthsForIntegers() {
        assertRejected("@Bitpack data class W(val x: Int)", "x", "needs @Bits")
        assertRejected("@Bitpack data class W(val x: Long)", "x", "needs @Bits")
    }

    @Test
    fun bitpackRejectsWidthsThatCannotWork() {
        assertRejected("@Bitpack data class W(@Bits(0) val x: Int)", "at least one bit")
        assertRejected("@Bitpack data class W(@Bits(-1) val x: Int)", "at least one bit")
        assertRejected("@Bitpack data class W(@Bits(33) val x: Int)", "33 bits", "only has 32")
        assertRejected("@Bitpack data class W(@Bits(9) val x: Byte)", "9 bits", "only has 8")
        assertRejected("@Bitpack data class W(@Bits(17) val x: Short)", "17 bits", "only has 16")
        assertRejected("@Bitpack data class W(@Bits(2) val x: Boolean)", "2 bits", "only has 1")
    }

    @Test
    fun bitpackChecksEnumWidths() {
        val colors = "enum class Color { RED, GREEN, BLUE, ALPHA, EXTRA }\n"
        assertAccepted(colors + "@Bitpack data class C(val color: Color)")
        assertAccepted(colors + "@Bitpack data class C(@Bits(3) val color: Color)")
        assertRejected(colors + "@Bitpack data class C(@Bits(2) val color: Color)", "5 enum entries", "at least 3")
    }

    @Test
    fun bitpackRejectsMoreThanSixtyFourBits() {
        assertAccepted("@Bitpack data class Fits(@Bits(32) val a: Int, @Bits(32) val b: Int)")
        assertRejected(
            "@Bitpack data class Big(@Bits(32) val a: Int, @Bits(32) val b: Int, val c: Boolean)",
            "more than 64 bits",
        )
    }

    @Test
    fun bitpackRejectsSignedFieldsThatCannotBeNegative() {
        assertRejected("@Bitpack data class S(@Bits(1, signed = true) val x: Boolean)", "cannot be signed")
        assertRejected(
            "enum class E { A }\n@Bitpack data class S(@Bits(1, signed = true) val x: E)",
            "cannot be signed",
        )
    }

    @Test
    fun bitpackRejectsShapesItCannotRebuild() {
        assertRejected("@Bitpack class NotProperty(flag: Boolean)", "flag", "must be a property")
        assertRejected("@Bitpack data class G<T>(val flag: Boolean, val other: T)", "generic")
        assertRejected("@Bitpack object O", "only applies to classes")
        assertRejected("@Bitpack class Empty", "at least one property")
    }

    @Test
    fun aWideStructGeneratesCodeThatStillCompiles() {
        // Sixty-four single-bit fields with long names make generated lines far wider than the
        // code generator's line limit. A wrapped line before an infix `or` would not compile.
        val fields = (0 until 64).joinToString(", ") { "val aReasonablyLongFieldNameNumber$it: Boolean" }
        assertAccepted("@Bitpack data class Wide($fields)")
        val diff = (0 until 64).joinToString(", ") { "val anotherQuiteLongPropertyNameNumber$it: Double" }
        assertAccepted("@FastDiff data class WideDiffInput($diff)")
    }

    @Test
    fun fastDiffReportsConstantNameCollisionsAtTheProperty() {
        assertRejected(
            "@FastDiff data class C(val fooBar: Int, val foo_bar: Int)",
            "properties fooBar and foo_bar",
            "CDiff.FOO_BAR",
        )
        assertRejected("@FastDiff data class C(val all: Int, val x: Int)", "property all", "CDiff.ALL", "reserved")
        assertAccepted("@FastDiff data class C(val fooBar: Int, @DiffIgnore val foo_bar: Int)")
        assertAccepted("@FastDiff data class C(@DiffIgnore val all: Int, val x: Int)")
    }

    @Test
    fun fastDiffAcceptsAliasesAndUnsignedArrays() {
        assertAccepted("typealias Celsius = Double\n@FastDiff data class A(val t: Celsius, val u: Celsius?)")
        assertAccepted(
            "@OptIn(ExperimentalUnsignedTypes::class) @FastDiff data class U(val a: UIntArray, val b: Array<IntArray>)",
        )
    }

    @Test
    fun bitpackAcceptsTheNewScalarTypes() {
        assertAccepted(
            "@Bitpack data class S(@Bits(7) val c: Char, @Bits(8) val a: UByte, @Bits(16) val b: UShort, " +
                "@Bits(1) val d: UInt, val f: Float)",
        )
        assertAccepted("@Bitpack data class D(val d: Double)")
        assertAccepted("@Bitpack data class L(@Bits(64) val d: ULong)")
        assertAccepted("enum class E { A, B, C }\n@Bitpack data class N(val e: E?, val f: Boolean)")
        assertAccepted("typealias Code = Int\n@Bitpack data class T(@Bits(4) val c: Code)")
    }

    @Test
    fun bitpackChecksTheNewScalarTypes() {
        assertRejected("@Bitpack data class W(val c: Char)", "c", "needs @Bits")
        assertRejected("@Bitpack data class W(val u: UInt)", "u", "needs @Bits")
        assertRejected("@Bitpack data class W(@Bits(17) val c: Char)", "17 bits", "only has 16")
        assertRejected("@Bitpack data class W(@Bits(9) val u: UByte)", "9 bits", "only has 8")
        assertRejected("@Bitpack data class W(@Bits(16) val f: Float)", "f is a Float", "IEEE 754", "given 16")
        assertRejected("@Bitpack data class W(@Bits(32) val d: Double)", "d is a Double", "given 32")
        assertRejected("@Bitpack data class W(@Bits(33) val f: Float)", "33 bits", "only has 32")
        assertRejected("@Bitpack data class W(@Bits(4, signed = true) val u: UInt)", "cannot be signed", "no negative")
        assertRejected("@Bitpack data class W(@Bits(4, signed = true) val c: Char)", "cannot be signed")
        assertRejected("@Bitpack data class W(@Bits(32, signed = true) val f: Float)", "cannot be signed", "IEEE 754")
        assertRejected("@Bitpack data class W(@Bits(8) val c: Char?)", "unsupported type")
    }

    @Test
    fun nullableEnumsNeedRoomForNull() {
        val colors = "enum class Color { RED, GREEN, BLUE, ALPHA }\n"
        assertAccepted(colors + "@Bitpack data class C(@Bits(2) val color: Color)")
        assertAccepted(colors + "@Bitpack data class C(@Bits(3) val color: Color?)")
        assertRejected(
            colors + "@Bitpack data class C(@Bits(2) val color: Color?)",
            "4 enum entries plus null",
            "at least 3",
        )
    }

    @Test
    fun bitpackRejectsMembersGeneratedCodeCannotReach() {
        assertRejected("@Bitpack data class P(@Bits(3) private val x: Int)", "private property x", "public or internal")
        assertRejected("@Bitpack class P(@Bits(3) protected val x: Int)", "protected property x")
        assertRejected(
            "@Bitpack data class P private constructor(@Bits(3) val x: Int)",
            "constructor of P is private",
            "unpack()",
        )
        assertAccepted("@Bitpack data class P internal constructor(@Bits(3) internal val x: Int)")
    }

    @Test
    fun bitpackReportsGeneratedNameClashesAtTheProperty() {
        assertRejected("@Bitpack data class R(@Bits(3) val raw: Int)", "property raw", "PackedR.raw", "packed bits")
        assertRejected(
            "@Bitpack data class W(@Bits(3) val x: Int, @Bits(3) val X: Int)",
            "property X",
            "PackedW.withX",
            "setter of property x",
        )
        assertRejected(
            "@Bitpack data class In(val f: Boolean)\n@Bitpack data class Out(val inner: In, val innerPacked: Boolean)",
            "property innerPacked",
            "PackedOut.innerPacked",
            "packed getter of property inner",
        )
    }

    @Test
    fun bitpackNestsOtherBitpackClasses() {
        val inner = "@Bitpack data class In(@Bits(3) val x: Int, val f: Boolean)\n"
        assertAccepted(inner + "@Bitpack data class Out(val a: In, @Bits(10) val b: In, val c: Boolean)")
        assertAccepted(inner + "@Bitpack data class Mid(val a: In)\n@Bitpack data class Top(val m: Mid, val n: In)")
        assertRejected(inner + "@Bitpack data class Out(@Bits(3) val a: In)", "a holds a packed In", "needs 4 bits")
        assertRejected(inner + "@Bitpack data class Out(@Bits(4, signed = true) val a: In)", "cannot be signed")
        assertRejected(inner + "@Bitpack data class Out(val a: In?)", "unsupported type")
        assertRejected(
            "@Bitpack data class In(@Bits(40) val x: Long)\n@Bitpack data class Out(val a: In, @Bits(30) val y: Int)",
            "more than 64 bits",
        )
        // A class without @Bitpack is not packable, however simple.
        assertRejected("data class Plain(val f: Boolean)\n@Bitpack data class Out(val p: Plain)", "unsupported type")
    }

    @Test
    fun bitpackExplainsWhyANestedClassCannotBePacked() {
        assertRejected(
            "@Bitpack data class In(val x: Int)\n@Bitpack data class Out(val inner: In)",
            "property inner has the type In, which cannot be packed",
            "needs @Bits",
        )
        assertRejected(
            "@Bitpack internal data class In(val f: Boolean)\n@Bitpack data class Out(val inner: In)",
            "Out is public",
            "inner has an internal type",
        )
        assertAccepted("@Bitpack internal data class In(val f: Boolean)\n@Bitpack internal data class Out(val inner: In)")
    }

    @Test
    fun bitpackRejectsClassesThatContainThemselves() {
        assertRejected("@Bitpack data class A(val a: A)", "contain itself", "A -> A")
        assertRejected(
            "@Bitpack data class A(val b: B)\n@Bitpack data class B(val a: A)",
            "(A -> B -> A)",
            "(B -> A -> B)",
        )
    }

    @Test
    fun eachOutputDependsOnlyOnItsOwnInputSoBuildsStayIncremental() {
        // Captured during the call, because symbols cannot be read once the KSP session ends.
        val recorded = ArrayList<Pair<Boolean, List<String>>>()
        val provider = SymbolProcessorProvider { environment ->
            BoronProcessor(
                object : CodeGenerator by environment.codeGenerator {
                    override fun createNewFile(
                        dependencies: Dependencies,
                        packageName: String,
                        fileName: String,
                        extensionName: String,
                    ): OutputStream {
                        recorded.add(dependencies.aggregating to dependencies.originatingFiles.map { it.fileName })
                        return environment.codeGenerator.createNewFile(
                            dependencies,
                            packageName,
                            fileName,
                            extensionName,
                        )
                    }
                },
                environment.logger,
            )
        }
        val result = compile(
            "@FastDiff data class A(val x: Int)\n@Bitpack data class B(val flag: Boolean)",
            provider,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(2, recorded.size)
        for ((aggregating, files) in recorded) {
            assertEquals(false, aggregating, "aggregating output defeats incremental builds")
            assertEquals(listOf("Input.kt"), files)
        }
    }
}
