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
        assertRejected("@Bitpack data class F(@Bits(8) val x: Float)", "x", "unsupported type")
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
