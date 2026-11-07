package dev.aetherx86.boron

/**
 * Generates a comparison that reports which properties of two instances differ, as a bit mask.
 *
 * For a class `Account` this generates `Account.diffMask(other)`, an `AccountDiff` object holding
 * one constant per property, and, for data classes, `Account.applyDiff(other, mask)`.
 *
 * Generated code uses no reflection and allocates nothing in `diffMask`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class FastDiff

/**
 * Generates a value class that stores the annotated class's properties packed into one `Long`.
 *
 * For a class `Flags` this generates `PackedFlags`, with a getter and a `with` function per
 * property, plus `Flags.pack()` and `PackedFlags.unpack()`. A `PackedFlags` is a plain `long` at
 * run time, so arrays of them need no objects.
 *
 * Supported property types are `Boolean`, `Byte`, `Short`, `Int`, `Long` and enum classes. The
 * total width cannot exceed 64 bits.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Bitpack

/**
 * The width of a [Bitpack] property, in bits.
 *
 * Required for integer properties. `Boolean` defaults to one bit, and an enum defaults to the
 * fewest bits that hold all of its entries. With [signed], a property uses two's complement and
 * can hold negative values.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Bits(val value: Int, val signed: Boolean = false)

/** Leaves a property out of [FastDiff] comparisons, for example a cached or derived value. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class DiffIgnore
