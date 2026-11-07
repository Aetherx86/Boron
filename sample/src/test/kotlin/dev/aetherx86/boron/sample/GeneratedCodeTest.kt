package dev.aetherx86.boron.sample

import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedCodeTest {
    private val base = AccountState(1, 100, "abc")

    @Test
    fun identicalValuesHaveAnEmptyMask() {
        assertEquals(0, base.diffMask(base))
        assertEquals(0, base.diffMask(base.copy()))
    }

    @Test
    fun eachPropertyHasItsOwnBit() {
        assertEquals(AccountStateDiff.NONCE, base.diffMask(base.copy(nonce = 2)))
        assertEquals(AccountStateDiff.BALANCE, base.diffMask(base.copy(balance = 2)))
        assertEquals(AccountStateDiff.CODE_HASH, base.diffMask(base.copy(codeHash = "z")))
        assertEquals(1, AccountStateDiff.NONCE)
        assertEquals(2, AccountStateDiff.BALANCE)
        assertEquals(4, AccountStateDiff.CODE_HASH)
        assertEquals(7, AccountStateDiff.ALL)
    }

    @Test
    fun severalChangesCombineAndAreSymmetric() {
        val changed = AccountState(9, 100, "z")
        val expected = AccountStateDiff.NONCE or AccountStateDiff.CODE_HASH
        assertEquals(expected, base.diffMask(changed))
        assertEquals(expected, changed.diffMask(base))
        assertEquals(AccountStateDiff.ALL, base.diffMask(AccountState(2, 3, "q")))
    }

    @Test
    fun namesDescribeAMask() {
        assertEquals(emptyList(), AccountStateDiff.names(0))
        assertEquals(
            listOf("nonce", "codeHash"),
            AccountStateDiff.names(AccountStateDiff.NONCE or AccountStateDiff.CODE_HASH),
        )
        assertEquals(listOf("nonce", "balance", "codeHash"), AccountStateDiff.names(AccountStateDiff.ALL))
    }

    @Test
    fun applyDiffCopiesExactlyTheMaskedProperties() {
        val other = AccountState(9, 900, "zzz")
        assertEquals(base, base.applyDiff(other, 0))
        assertEquals(other, base.applyDiff(other, AccountStateDiff.ALL))
        assertEquals(
            AccountState(9, 100, "zzz"),
            base.applyDiff(other, AccountStateDiff.NONCE or AccountStateDiff.CODE_HASH),
        )
    }

    @Test
    fun applyingADiffReproducesTheTargetState() {
        val random = Random(4)
        repeat(1000) {
            val from = AccountState(random.nextLong(4), random.nextLong(4), random.nextInt(4).toString())
            val to = AccountState(random.nextLong(4), random.nextLong(4), random.nextInt(4).toString())
            assertEquals(to, from.applyDiff(to, from.diffMask(to)))
        }
    }

    @Test
    fun diffMaskAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val a = AccountState(1, 2, "x")
        val b = AccountState(1, 3, "y")
        var sink = 0
        repeat(20_000) { sink += a.diffMask(b) }
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        repeat(1_000_000) { sink += a.diffMask(b) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 4096, "allocated $allocated bytes")
        assertTrue(sink != 1)
    }

    private val measurement = Measurements(1.0, 2f, "a", 3.0, intArrayOf(1, 2), arrayOf("x"))

    @Test
    fun nanDoesNotCountAsAChange() {
        // With plain `!=` on a Double, NaN would differ from itself, so an untouched value would
        // be reported as changed forever.
        val withNan = measurement.copy(temperature = Double.NaN, ratio = Float.NaN, maybe = Double.NaN)
        assertEquals(0, withNan.diffMask(withNan.copy()))
    }

    @Test
    fun negativeZeroCountsAsAChange() {
        val positive = measurement.copy(temperature = 0.0)
        val negative = measurement.copy(temperature = -0.0)
        assertEquals(MeasurementsDiff.TEMPERATURE, positive.diffMask(negative))
    }

    @Test
    fun nullableFloatsCompareSanely() {
        val a = measurement.copy(maybe = null)
        val b = measurement.copy(maybe = 1.0)
        assertEquals(MeasurementsDiff.MAYBE, a.diffMask(b))
        assertEquals(0, a.diffMask(a.copy()))
    }

    @Test
    fun arraysCompareByContentNotIdentity() {
        val a = measurement.copy(samples = intArrayOf(1, 2), tags = arrayOf("x"))
        val b = measurement.copy(samples = intArrayOf(1, 2), tags = arrayOf("x"))
        assertEquals(0, a.diffMask(b), "equal contents in different arrays are not a change")
        assertEquals(MeasurementsDiff.SAMPLES, a.diffMask(a.copy(samples = intArrayOf(1, 3))))
        assertEquals(MeasurementsDiff.TAGS, a.diffMask(a.copy(tags = arrayOf("y"))))
    }

    @Test
    fun nullablePropertiesCompareCorrectly() {
        val a = measurement.copy(label = null)
        assertEquals(MeasurementsDiff.LABEL, a.diffMask(a.copy(label = "x")))
        assertEquals(MeasurementsDiff.LABEL, a.copy(label = "x").diffMask(a))
        assertEquals(0, a.diffMask(a.copy(label = null)))
    }

    @Test
    fun ignoredPropertiesAreLeftOut() {
        val a = WithIgnored(1, 2, "x")
        assertEquals(0, a.diffMask(a.copy(cached = 99)))
        assertEquals(WithIgnoredDiff.ALSO_KEPT, a.diffMask(a.copy(alsoKept = "y")))
        assertEquals(3, WithIgnoredDiff.ALL, "only the two kept properties have bits")
        val other = WithIgnored(5, 6, "y")
        assertEquals(2, a.applyDiff(other, WithIgnoredDiff.ALL).cached, "the ignored property is kept")
    }

    @Test
    fun genericClassesAreSupported() {
        val a = Generic("x", 1, listOf("p"))
        assertEquals(0, a.diffMask(Generic("x", 1, listOf("p"))))
        assertEquals(GenericDiff.SECOND, a.diffMask(a.copy(second = 2)))
        assertEquals(a.copy(first = "y"), a.applyDiff(a.copy(first = "y"), GenericDiff.FIRST))
    }

    @Test
    fun plainClassesIncludeBodyPropertiesAndGetters() {
        val a = NotData(1, "x")
        val b = NotData(1, "y")
        assertEquals(NotDataDiff.B, a.diffMask(b))
        assertEquals(NotDataDiff.A or NotDataDiff.DERIVED, a.diffMask(NotData(2, "x")))
    }

    @Test
    fun inheritedPropertiesAreCompared() {
        val a = Derived(1, 2)
        assertEquals(DerivedDiff.INHERITED, a.diffMask(Derived(9, 2)))
        assertEquals(DerivedDiff.OWN, a.diffMask(Derived(1, 9)))
    }

    @Test
    fun nestedAndInternalClassesAreSupported() {
        assertEquals(Outer_NestedDiff.VALUE, Outer.Nested(1).diffMask(Outer.Nested(2)))
        assertEquals(InternalStateDiff.VALUE, InternalState(1).diffMask(InternalState(2)))
    }

    @Test
    fun maskWidensToLongAtThirtyThreeProperties() {
        val a = Wide(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val mask: Long = a.diffMask(a.copy(p32 = 1))
        assertEquals(1L shl 32, mask, "bit 32 only exists in a Long mask")
        assertEquals((1L shl 33) - 1, WideDiff.ALL)
        assertEquals(1L shl 31, a.diffMask(a.copy(p31 = 1)), "bit 31 must not sign-extend")
        assertEquals(a.copy(p32 = 5), a.applyDiff(a.copy(p32 = 5), WideDiff.P32))
    }

    @Test
    fun anIntMaskUsesItsSignBitCorrectly() {
        val a = ExactlyThirtyTwo(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val mask: Int = a.diffMask(a.copy(p31 = 1))
        assertEquals(Int.MIN_VALUE, mask)
        assertEquals(-1, ExactlyThirtyTwoDiff.ALL)
        assertEquals(listOf("p31"), ExactlyThirtyTwoDiff.names(mask))
        assertEquals(a.copy(p31 = 7), a.applyDiff(a.copy(p31 = 7), mask))
    }

    /** Only compiles if the processor deferred this class until `PackedParticle` existed. */
    @Test
    fun aClassReferringToGeneratedTypesIsProcessedInALaterRound() {
        val p = Particle(Material.SAND, 1, false, 0, true).pack()
        val a = UsesGenerated(p, "x")
        assertEquals(UsesGeneratedDiff.NOTE, a.diffMask(a.copy(note = "y")))
        assertEquals(UsesGeneratedDiff.PACKED, a.diffMask(a.copy(packed = p.withVariation(2))))
    }

    private val particle = Particle(Material.WATER, 200, true, -300, false)

    @Test
    fun packingRoundTrips() {
        assertEquals(particle, particle.pack().unpack())
        val packed = particle.pack()
        assertEquals(Material.WATER, packed.material)
        assertEquals(200, packed.variation)
        assertTrue(packed.moved)
        assertEquals(-300, packed.velocity)
        assertFalse(packed.active)
    }

    @Test
    fun theLayoutIsDeclarationOrderFromBitZero() {
        // material takes 3 bits for 5 entries, then variation 8, moved 1, velocity 12, active 1.
        assertEquals(25, PackedParticle_BITS)
        val packed = Particle(Material.STONE, 0, false, 0, false).pack()
        assertEquals(3L, packed.raw)
        assertEquals(1L shl 3, Particle(Material.EMPTY, 1, false, 0, false).pack().raw)
        assertEquals(1L shl 11, Particle(Material.EMPTY, 0, true, 0, false).pack().raw)
        assertEquals(1L shl 12, Particle(Material.EMPTY, 0, false, 1, false).pack().raw)
        assertEquals(1L shl 24, Particle(Material.EMPTY, 0, false, 0, true).pack().raw)
    }

    @Test
    fun withFunctionsChangeOnlyTheirOwnField() {
        val packed = particle.pack()
        assertEquals(particle.copy(material = Material.FIRE), packed.withMaterial(Material.FIRE).unpack())
        assertEquals(particle.copy(variation = 1), packed.withVariation(1).unpack())
        assertEquals(particle.copy(moved = false), packed.withMoved(false).unpack())
        assertEquals(particle.copy(velocity = 2047), packed.withVelocity(2047).unpack())
        assertEquals(particle.copy(velocity = -2048), packed.withVelocity(-2048).unpack())
        assertEquals(particle.copy(active = true), packed.withActive(true).unpack())
    }

    @Test
    fun valuesOutsideTheirWidthAreRejected() {
        val packed = particle.pack()
        assertFailsWith<IllegalArgumentException> { packed.withVariation(256) }
        assertFailsWith<IllegalArgumentException> { packed.withVariation(-1) }
        assertFailsWith<IllegalArgumentException> { packed.withVelocity(2048) }
        assertFailsWith<IllegalArgumentException> { packed.withVelocity(-2049) }
        assertFailsWith<IllegalArgumentException> { particle.copy(variation = 300).pack() }
        val message = assertFailsWith<IllegalArgumentException> { packed.withVariation(256) }.message!!
        assertTrue("variation" in message && "256" in message, message)
    }

    @Test
    fun everyIntegerTypeRoundTripsAtItsLimits() {
        val cases = listOf(
            AllTypes(0, 0, 0, 0, false, Single.ONLY),
            AllTypes(31, 1023, (1 shl 20) - 1, (1L shl 24) - 1, true, Single.ONLY),
            AllTypes(1, 2, 3, 4, true, Single.ONLY),
        )
        for (value in cases) assertEquals(value, value.pack().unpack())
        assertFailsWith<IllegalArgumentException> { AllTypes(32, 0, 0, 0, false, Single.ONLY).pack() }
        assertFailsWith<IllegalArgumentException> { AllTypes(0, 1024, 0, 0, false, Single.ONLY).pack() }
        assertFailsWith<IllegalArgumentException> { AllTypes(0, 0, 1 shl 20, 0, false, Single.ONLY).pack() }
        assertFailsWith<IllegalArgumentException> { AllTypes(0, 0, 0, 1L shl 24, false, Single.ONLY).pack() }
    }

    @Test
    fun aFullSixtyFourBitLayoutWorks() {
        assertEquals(64, PackedFullWidth_BITS)
        for ((low, high) in listOf(0 to 0, -1 to -1, Int.MIN_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to Int.MIN_VALUE, 1 to -1)) {
            val value = FullWidth(low, high)
            assertEquals(value, value.pack().unpack())
            assertEquals(value.copy(low = 5), value.pack().withLow(5).unpack())
            assertEquals(value.copy(high = 5), value.pack().withHigh(5).unpack())
        }
        for (value in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(Whole(value), Whole(value).pack().unpack())
            assertEquals(value, PackedWhole(value).value)
        }
    }

    @Test
    fun signedFieldsExtendAtEveryWidth() {
        val edges = listOf(
            SignedEdges(0, 0, 0, 0),
            SignedEdges(-1, -128, Short.MIN_VALUE, -(1L shl 38)),
            SignedEdges(0, 127, Short.MAX_VALUE, (1L shl 38) - 1),
            SignedEdges(-1, -1, -1, -1),
        )
        for (value in edges) assertEquals(value, value.pack().unpack())
        assertFailsWith<IllegalArgumentException> { SignedEdges(1, 0, 0, 0).pack() }
        assertFailsWith<IllegalArgumentException> { SignedEdges(-2, 0, 0, 0).pack() }
        assertFailsWith<IllegalArgumentException> { SignedEdges(0, 0, 0, 1L shl 38).pack() }
        assertFailsWith<IllegalArgumentException> { SignedEdges(0, 0, 0, -(1L shl 38) - 1).pack() }
    }

    @Test
    fun randomValuesRoundTripAndKeepNeighborsIntact() {
        val random = Random(9)
        repeat(20_000) {
            val value = Particle(
                Material.entries[random.nextInt(Material.entries.size)],
                random.nextInt(256),
                random.nextBoolean(),
                random.nextInt(4096) - 2048,
                random.nextBoolean(),
            )
            assertEquals(value, value.pack().unpack())
            val velocity = random.nextInt(4096) - 2048
            assertEquals(value.copy(velocity = velocity), value.pack().withVelocity(velocity).unpack())
        }
    }

    @Test
    fun packedValuesAreRawLongsAtRunTime() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        var packed = Particle(Material.SAND, 1, false, 1, false).pack()
        repeat(20_000) { packed = packed.withVariation(it and 0xFF) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(1_000_000) { packed = packed.withVariation(it and 0xFF).withVelocity(it and 0x3FF) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 4096, "allocated $allocated bytes")
        assertTrue(packed.raw != Long.MIN_VALUE)
    }

    @Test
    fun generatedCodeUsesNoReflection() {
        val classes = listOf(
            Class.forName("dev.aetherx86.boron.sample.AccountStateDiffKt"),
            Class.forName("dev.aetherx86.boron.sample.PackedParticle"),
        )
        for (type in classes) {
            val resource = type.name.replace('.', '/') + ".class"
            val bytes = type.classLoader.getResourceAsStream(resource)!!.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            assertFalse("java/lang/reflect" in text, "$type refers to reflection")
            assertFalse("kotlin/reflect" in text, "$type refers to reflection")
        }
    }
}
