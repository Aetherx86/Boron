package dev.aetherx86.boron.sample

import dev.aetherx86.boron.Bitpack
import dev.aetherx86.boron.Bits
import dev.aetherx86.boron.DiffIgnore
import dev.aetherx86.boron.FastDiff

@FastDiff
data class AccountState(
    val nonce: Long,
    val balance: Long,
    val codeHash: String,
)

@FastDiff
data class Measurements(
    val temperature: Double,
    val ratio: Float,
    val label: String?,
    val maybe: Double?,
    val samples: IntArray,
    val tags: Array<String>,
)

@FastDiff
data class WithIgnored(
    val kept: Int,
    @DiffIgnore val cached: Int,
    val alsoKept: String,
)

@FastDiff
data class Generic<T, U : Comparable<U>>(
    val first: T,
    val second: U,
    val items: List<T>,
)

/** A plain class gets a diff, but no `applyDiff`, because it has no `copy`. */
@FastDiff
class NotData(val a: Int, var b: String) {
    val derived: Int get() = a * 2
}

open class Base(val inherited: Int)

@FastDiff
class Derived(inherited: Int, val own: Int) : Base(inherited)

class Outer {
    @FastDiff
    data class Nested(val value: Int)
}

@FastDiff
internal data class InternalState(val value: Int)

/** Exactly 33 properties, which pushes the mask from Int to Long. */
@FastDiff
data class Wide(
    val p00: Int, val p01: Int, val p02: Int, val p03: Int, val p04: Int, val p05: Int, val p06: Int,
    val p07: Int, val p08: Int, val p09: Int, val p10: Int, val p11: Int, val p12: Int, val p13: Int,
    val p14: Int, val p15: Int, val p16: Int, val p17: Int, val p18: Int, val p19: Int, val p20: Int,
    val p21: Int, val p22: Int, val p23: Int, val p24: Int, val p25: Int, val p26: Int, val p27: Int,
    val p28: Int, val p29: Int, val p30: Int, val p31: Int, val p32: Int,
)

/** Exactly 32 properties, the largest size that still uses an Int mask. */
@FastDiff
data class ExactlyThirtyTwo(
    val p00: Int, val p01: Int, val p02: Int, val p03: Int, val p04: Int, val p05: Int, val p06: Int,
    val p07: Int, val p08: Int, val p09: Int, val p10: Int, val p11: Int, val p12: Int, val p13: Int,
    val p14: Int, val p15: Int, val p16: Int, val p17: Int, val p18: Int, val p19: Int, val p20: Int,
    val p21: Int, val p22: Int, val p23: Int, val p24: Int, val p25: Int, val p26: Int, val p27: Int,
    val p28: Int, val p29: Int, val p30: Int, val p31: Int,
)

enum class Material { EMPTY, SAND, WATER, STONE, FIRE }

enum class Single { ONLY }

@Bitpack
data class Particle(
    val material: Material,
    @Bits(8) val variation: Int,
    @Bits(1) val moved: Boolean,
    @Bits(12, signed = true) val velocity: Int,
    val active: Boolean,
)

@Bitpack
data class AllTypes(
    @Bits(5) val byteField: Byte,
    @Bits(10) val shortField: Short,
    @Bits(20) val intField: Int,
    @Bits(24) val longField: Long,
    val flag: Boolean,
    val single: Single,
)

/** Uses every one of the 64 bits, which is where shift and mask arithmetic tends to break. */
@Bitpack
data class FullWidth(
    @Bits(32) val low: Int,
    @Bits(32) val high: Int,
)

@Bitpack
data class Whole(@Bits(64) val value: Long)

@Bitpack
data class SignedEdges(
    @Bits(1, signed = true) val one: Int,
    @Bits(8, signed = true) val byteSized: Byte,
    @Bits(16, signed = true) val shortSized: Short,
    @Bits(39, signed = true) val wide: Long,
)

/**
 * Refers to a type generated from another annotation in this same file, so it cannot be processed
 * in the first round. This only compiles if deferring unresolved symbols works.
 */
@FastDiff
data class UsesGenerated(
    val packed: PackedParticle,
    val note: String,
)
