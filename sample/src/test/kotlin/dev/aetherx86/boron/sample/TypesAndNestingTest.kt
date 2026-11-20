package dev.aetherx86.boron.sample

import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers what 0.2.0 added: alias and array comparison, new packed types, and nesting. */
@OptIn(ExperimentalUnsignedTypes::class)
class TypesAndNestingTest {
    private fun allocatedBy(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        repeat(3) { block() }
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    // ---- FastDiff ----

    @Test
    fun aliasesOfDoubleKeepNanSafeComparison() {
        val a = Aliased(Double.NaN, Double.NaN, intArrayOf(1, 2))
        assertEquals(0, a.diffMask(a.copy()), "NaN behind a typealias must not count as a change")
        assertEquals(AliasedDiff.TEMPERATURE, a.copy(temperature = 0.0).diffMask(a.copy(temperature = -0.0)))
        assertEquals(AliasedDiff.MAYBE, a.diffMask(a.copy(maybe = null)))
    }

    @Test
    fun aliasesOfArraysCompareByContents() {
        val a = Aliased(1.0, null, intArrayOf(1, 2))
        assertEquals(0, a.diffMask(a.copy(samples = intArrayOf(1, 2))))
        assertEquals(AliasedDiff.SAMPLES, a.diffMask(a.copy(samples = intArrayOf(2, 1))))
    }

    @Test
    fun nestedArraysCompareByContents() {
        val a = Grid(arrayOf(intArrayOf(1), intArrayOf(2, 3)), arrayOf(arrayOf("x"), arrayOf()))
        val same = Grid(arrayOf(intArrayOf(1), intArrayOf(2, 3)), arrayOf(arrayOf("x"), arrayOf()))
        assertEquals(0, a.diffMask(same))
        assertEquals(GridDiff.CELLS, a.diffMask(same.copy(cells = arrayOf(intArrayOf(1), intArrayOf(2, 4)))))
        assertEquals(GridDiff.LAYERS, a.diffMask(same.copy(layers = arrayOf(arrayOf("y"), arrayOf()))))
        assertEquals(GridDiff.ALL, a.diffMask(Grid(arrayOf(), arrayOf())))
    }

    @Test
    fun unsignedArraysCompareByContents() {
        val a = UnsignedBuffers(ubyteArrayOf(1u), ushortArrayOf(2u), uintArrayOf(3u), ulongArrayOf(4u))
        val same = UnsignedBuffers(ubyteArrayOf(1u), ushortArrayOf(2u), uintArrayOf(3u), ulongArrayOf(4u))
        assertEquals(0, a.diffMask(same))
        assertEquals(UnsignedBuffersDiff.BYTES, a.diffMask(same.copy(bytes = ubyteArrayOf(255u))))
        assertEquals(UnsignedBuffersDiff.SHORTS, a.diffMask(same.copy(shorts = ushortArrayOf())))
        assertEquals(UnsignedBuffersDiff.INTS, a.diffMask(same.copy(ints = uintArrayOf(3u, 3u))))
        assertEquals(UnsignedBuffersDiff.LONGS, a.diffMask(same.copy(longs = ulongArrayOf(ULong.MAX_VALUE))))
        val other = UnsignedBuffers(ubyteArrayOf(9u), ushortArrayOf(9u), uintArrayOf(9u), ulongArrayOf(9u))
        assertEquals(0, other.diffMask(a.applyDiff(other, UnsignedBuffersDiff.ALL)))
    }

    // ---- New packed types ----

    private val scalars = Scalars('Z', 5u, 0xBEEFu, 31u, 1uL, -1.5f)

    @Test
    fun newScalarTypesRoundTrip() {
        assertEquals(64, PackedScalars_BITS)
        val packed = scalars.pack()
        assertEquals(scalars, packed.unpack())
        assertEquals('Z', packed.ascii)
        assertEquals(5u.toUByte(), packed.tiny)
        assertEquals(0xBEEFu.toUShort(), packed.word)
        assertEquals(31u, packed.count)
        assertEquals(1uL, packed.bit)
        assertEquals(-1.5f, packed.ratio)
        assertEquals(scalars.copy(ascii = '\u0000'), packed.withAscii('\u0000').unpack())
        assertEquals(scalars.copy(word = UShort.MAX_VALUE), packed.withWord(UShort.MAX_VALUE).unpack())
        assertEquals(scalars.copy(ratio = Float.MAX_VALUE), packed.withRatio(Float.MAX_VALUE).unpack())
    }

    @Test
    fun unsignedAndCharFieldsRejectValuesTooWide() {
        val packed = scalars.pack()
        assertFailsWith<IllegalArgumentException> { packed.withAscii('\u0080') }
        assertFailsWith<IllegalArgumentException> { packed.withTiny(8u) }
        assertFailsWith<IllegalArgumentException> { packed.withCount(32u) }
        assertFailsWith<IllegalArgumentException> { packed.withBit(2uL) }
        assertFailsWith<IllegalArgumentException> { scalars.copy(count = UInt.MAX_VALUE).pack() }
        val message = assertFailsWith<IllegalArgumentException> { packed.withCount(4000000000u) }.message!!
        assertTrue("count" in message && "4000000000" in message, message)
    }

    @Test
    fun aFullWidthUnsignedLongKeepsItsTopBit() {
        for (value in listOf(0uL, 1uL, ULong.MAX_VALUE, 1uL shl 63, (1uL shl 63) - 1uL)) {
            assertEquals(WholeULong(value), WholeULong(value).pack().unpack())
        }
        assertEquals(-1L, WholeULong(ULong.MAX_VALUE).pack().raw)
    }

    @Test
    fun floatsAndDoublesKeepTheirExactBits() {
        val floats = listOf(0f, -0f, Float.MIN_VALUE, Float.NEGATIVE_INFINITY, Float.fromBits(0x7FC0_1234))
        for (value in floats) {
            val back = scalars.copy(ratio = value).pack().ratio
            assertEquals(value.toRawBits(), back.toRawBits(), "bits of $value")
        }
        val doubles = listOf(0.0, -0.0, Double.MIN_VALUE, Double.POSITIVE_INFINITY, Double.fromBits(0x7FF8_0000_0000_BEEFL))
        for (value in doubles) {
            val packed = WholeDouble(value).pack()
            assertEquals(value.toRawBits(), packed.raw)
            assertEquals(value.toRawBits(), packed.value.toRawBits())
        }
        // The neighbors of a Float field are untouched by a negative value's sign-extended bits.
        val negative = scalars.copy(ratio = -0f).pack()
        assertEquals(scalars.copy(ratio = -0f).copy(ascii = 'Z'), negative.unpack())
    }

    @Test
    fun nullableEnumsReserveZeroForNull() {
        // Five entries plus null take three bits, one entry plus null takes one more.
        assertEquals(5, PackedOptionalMaterial_BITS)
        val none = OptionalMaterial(null, null, false)
        assertEquals(0L, none.pack().raw)
        assertNull(none.pack().material)
        assertEquals(none, none.pack().unpack())
        for (material in Material.entries) {
            for (single in listOf(null, Single.ONLY)) {
                val value = OptionalMaterial(material, single, true)
                assertEquals(value, value.pack().unpack())
                assertEquals(material.ordinal + 1L, value.pack().raw and 0x7L)
            }
        }
        val packed = OptionalMaterial(Material.FIRE, Single.ONLY, true).pack()
        assertEquals(OptionalMaterial(null, Single.ONLY, true), packed.withMaterial(null).unpack())
        assertEquals(OptionalMaterial(Material.FIRE, null, true), packed.withSingle(null).unpack())
    }

    // ---- Nesting ----

    private val particle = Particle(Material.WATER, 200, true, -300, false)
    private val cell = Cell(particle, Position(-512, 511), true)

    @Test
    fun nestedClassesAreEmbeddedAsTheirPackedBits() {
        // Particle takes 25 bits, Position is padded to 24, then one more for visible.
        assertEquals(25, PackedParticle_BITS)
        assertEquals(20, PackedPosition_BITS)
        assertEquals(50, PackedCell_BITS)
        val packed = cell.pack()
        assertEquals(cell, packed.unpack())
        assertEquals(particle.pack(), packed.particlePacked)
        assertEquals(Position(-512, 511).pack(), packed.sparePacked)
        assertEquals(particle.pack().raw, packed.raw and ((1L shl 25) - 1))
        assertEquals(Position(-512, 511).pack().raw, (packed.raw ushr 25) and ((1L shl 24) - 1))
        assertEquals(1L, packed.raw ushr 49)
    }

    @Test
    fun nestedSettersAcceptBothFormsAndKeepNeighbors() {
        val packed = cell.pack()
        val other = Particle(Material.FIRE, 1, false, 2047, true)
        assertEquals(cell.copy(particle = other), packed.withParticle(other).unpack())
        assertEquals(cell.copy(particle = other), packed.withParticle(other.pack()).unpack())
        assertEquals(cell.copy(spare = Position(0, 0)), packed.withSpare(Position(0, 0).pack()).unpack())
        assertEquals(cell.copy(visible = false), packed.withVisible(false).unpack())
    }

    @Test
    fun nestedValuesAreCheckedAgainstTheirOwnWidths() {
        val packed = cell.pack()
        assertFailsWith<IllegalArgumentException> { packed.withParticle(particle.copy(variation = 300)) }
        assertFailsWith<IllegalArgumentException> { cell.copy(spare = Position(512, 0)).pack() }
        val stray = assertFailsWith<IllegalArgumentException> {
            packed.withParticle(PackedParticle(1L shl 25))
        }.message!!
        assertTrue("particle" in stray && "25 bits" in stray, stray)
        // The padded field accepts bits up to its declared 24, even beyond Position's own 20.
        assertEquals(1L shl 23, packed.withSpare(PackedPosition(1L shl 23)).sparePacked.raw)
        assertFailsWith<IllegalArgumentException> { packed.withSpare(PackedPosition(1L shl 24)) }
    }

    @Test
    fun threeLevelsOfNestingRoundTrip() {
        assertEquals(54, PackedTagged_BITS)
        val random = Random(21)
        repeat(5_000) {
            val value = Tagged(
                Cell(
                    Particle(
                        Material.entries[random.nextInt(Material.entries.size)],
                        random.nextInt(256),
                        random.nextBoolean(),
                        random.nextInt(4096) - 2048,
                        random.nextBoolean(),
                    ),
                    Position(random.nextInt(1024) - 512, random.nextInt(1024) - 512),
                    random.nextBoolean(),
                ),
                random.nextInt(16).toUByte(),
            )
            val packed = value.pack()
            assertEquals(value, packed.unpack())
            assertEquals(value.cell.particle, packed.cellPacked.particlePacked.unpack())
            val tag = random.nextInt(16).toUByte()
            assertEquals(value.copy(tag = tag), packed.withTag(tag).unpack())
        }
    }

    @Test
    fun packedNestedAccessAllocatesNothing() {
        var packed = cell.pack()
        val inner = Particle(Material.SAND, 3, false, 7, true).pack()
        var sink = 0L
        val allocated = allocatedBy {
            repeat(1_000_000) {
                packed = packed.withParticle(inner.withVariation(it and 0xFF))
                sink += packed.particlePacked.raw + packed.sparePacked.raw
                packed = packed.withSpare(packed.sparePacked)
            }
        }
        assertTrue(allocated < 4096, "allocated $allocated bytes")
        assertTrue(sink != 1L)
    }

    @Test
    fun newScalarSettersAllocateNothing() {
        var packed = scalars.pack()
        val allocated = allocatedBy {
            repeat(1_000_000) {
                packed = packed.withCount((it and 31).toUInt())
                    .withRatio(it.toFloat())
                    .withAscii((it and 0x7F).toChar())
            }
        }
        assertTrue(allocated < 4096, "allocated $allocated bytes")
        assertTrue(packed.raw != 1L)
    }
}
