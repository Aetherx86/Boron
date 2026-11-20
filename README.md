# Boron

A KSP2 processor that writes the boring, fast code for you. Annotate a class, and you get a diff that
uses no reflection and allocates nothing, or a value class that packs its fields into a single `Long`.

```kotlin
@FastDiff
data class AccountState(val nonce: Long, val balance: Long, val codeHash: String)

val mask = before.diffMask(after)
if (mask and AccountStateDiff.BALANCE != 0) chargeFee()
AccountStateDiff.names(mask)         // ["balance"]
before.applyDiff(after, mask)        // equal to `after`
```

```kotlin
enum class Material { EMPTY, SAND, WATER, STONE, FIRE }

@Bitpack
data class Particle(
    val material: Material,                     // 3 bits, worked out from the five entries
    @Bits(8) val variation: Int,
    @Bits(12, signed = true) val velocity: Int,
    val active: Boolean,                        // 1 bit
)

val cell: PackedParticle = particle.pack()      // a plain `long` at run time
cell.velocity                                   // no object, no boxing
val moved = cell.withVelocity(-300)
val cells = LongArray(1 shl 20)                 // a million particles, no objects
```

## Setup

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.13"
}

dependencies {
    implementation("dev.aetherx86.boron:boron-annotations:0.2.0")
    ksp("dev.aetherx86.boron:boron-processor:0.2.0")
}
```

Both artifacts are published to GitHub Packages, at `https://maven.pkg.github.com/Aetherx86/Boron`.

## What gets generated

For `@FastDiff class Foo`:

| Generated | Purpose |
| --- | --- |
| `Foo.diffMask(other)` | A mask with one bit per differing property. `Int` up to 32 properties, `Long` up to 64. |
| `FooDiff.PROPERTY_NAME` | A constant for each property's bit, plus `ALL`. |
| `FooDiff.names(mask)` | Property names, for logging. This one allocates. |
| `Foo.applyDiff(other, mask)` | Data classes only: a copy holding `other`'s value for each set bit. |

`@DiffIgnore` leaves a property out. Nested classes flatten their names, so `Outer.Inner` generates
`Outer_InnerDiff`. Generic classes are supported.

For `@Bitpack class Foo`:

| Generated | Purpose |
| --- | --- |
| `PackedFoo(raw: Long)` | A `@JvmInline value class`, with one getter per property. |
| `PackedFoo.withProperty(value)` | A copy with one field replaced. |
| `Foo.pack()` and `PackedFoo.unpack()` | Conversion in both directions. |
| `PackedFoo_BITS` | How many of the 64 bits the layout uses. |

Fields are laid out in declaration order from bit 0. Supported types are `Boolean`, `Byte`, `Short`, `Int`,
`Long`, `Char`, `UByte`, `UShort`, `UInt`, `ULong`, `Float`, `Double`, enum classes (nullable or not) and
other `@Bitpack` classes from the same module. A value too big for its field throws
`IllegalArgumentException`, instead of silently corrupting its neighbors.

## Details that are easy to get wrong

- **NaN is equal to itself here.** With plain `!=`, a property holding NaN would count as changed on every
  comparison, because Kotlin uses IEEE comparison for `Double` and also for `Double?`. Generated code uses
  a total order instead, and `0.0` and `-0.0` count as different.
- **Arrays compare by contents**, rather than by identity. That includes arrays of arrays, which use
  `contentDeepEquals`, and unsigned arrays such as `UIntArray`, whose own `equals` compares identity.
- **Type aliases compare like the type they stand for**, so `typealias Celsius = Double` is NaN-safe too.
- **Generated code never uses reflection**, and a test scans the compiled classes to confirm it.
- **`diffMask` and the packed setters allocate nothing**, measured by a test with the JVM's per-thread
  allocation counter.

## How the processor behaves

- **Incremental.** One input file produces one output file, declared `aggregating = false`, so KSP only
  re-runs for the file you changed. A test intercepts the code generator to check this.
- **Multi-round.** A class referring to a type that has not been generated yet is deferred to the next
  round, so one annotated class can use another's generated type.
- **Clear errors.** Mistakes are reported at the offending declaration, in terms of the problem. These
  include a private property, a field wider than its type, an enum that does not fit, more than 64
  bits or properties, and unsupported types.

## How it is tested

- A sample module runs the real processor in a real Gradle build, and its tests exercise the generated
  code, including layouts that use all 64 bits, sign extension at every width and random round trips.
- Negative tests compile deliberately wrong programs through the real compiler and check the message.
- Known bugs were injected into the generator on purpose, such as a dropped deferral, a missing range
  check, a lost sign extension and identity comparison of arrays. Each one made the suite fail.

## New in 0.2.0

### More packed types

```kotlin
@Bitpack
data class Scalars(
    @Bits(7) val ascii: Char,          // unsigned, range checked like any whole number
    @Bits(3) val tiny: UByte,
    @Bits(16) val word: UShort,
    @Bits(5) val count: UInt,
    @Bits(1) val bit: ULong,
    val ratio: Float,                  // always 32 bits: the exact raw IEEE 754 bits
)

@Bitpack
data class OptionalMaterial(val material: Material?, val single: Single?, val flag: Boolean)
// null is code 0 and each entry is its ordinal plus one, so five entries plus null take 3 bits
```

| Type | Width | Notes |
| --- | --- | --- |
| `Char`, `UByte`, `UShort`, `UInt`, `ULong` | `@Bits` required | Unsigned. Cannot be `signed`. |
| `Float`, `Double` | Always 32 or 64 | Raw bits, so NaN payloads and `-0.0` survive a round trip. |
| Nullable enum | Entries plus one, or `@Bits` | `null` packs to 0. |

### Nesting

A property whose type is another `@Bitpack` class is embedded as that class's packed bits:

```kotlin
@Bitpack
data class Position(@Bits(10, signed = true) val x: Int, @Bits(10, signed = true) val y: Int)

@Bitpack
data class Cell(
    val particle: Particle,            // 25 bits, Particle's own width
    @Bits(24) val spare: Position,     // 20 bits needed, 4 more reserved for growth
    val visible: Boolean,
)

val cell = Cell(particle, Position(-512, 511), true).pack()
cell.particle                          // a Particle, rebuilt from its bits
cell.particlePacked                    // a PackedParticle, no object built
cell.withParticle(other.pack())        // also takes a Particle; checks stray bits
```

The nested class must be in the same module, because `@Bitpack` is a source-only annotation.
Nesting works to any depth, and a class that contains itself is reported with its loop,
for example `@Bitpack property a makes A contain itself (A -> B -> A)`.

### Diagnostics

These now fail at the offending declaration, instead of as an error inside a generated file:

| Mistake | Message, in part |
| --- | --- |
| `@FastDiff data class C(val fooBar: Int, val foo_bar: Int)` | `properties fooBar and foo_bar both generate the constant CDiff.FOO_BAR` |
| `@FastDiff data class C(val all: Int, val x: Int)` | `would generate the constant CDiff.ALL, which is reserved` |
| `@Bitpack data class R(@Bits(3) val raw: Int)` | `would generate PackedR.raw, which clashes with the property that holds the packed bits` |
| `@Bitpack data class P(@Bits(3) private val x: Int)` | `generated code cannot read the private property x` |
| `@Bitpack data class P private constructor(...)` | `the constructor of P is private` |
| `@Bitpack data class W(@Bits(16) val f: Float)` | `f is a Float, which is stored as all 32 of its IEEE 754 bits` |
| A public class nesting an internal `@Bitpack` class | `Out is public, but its @Bitpack property inner has an internal type` |

### Differential testing

`DifferentialTest` writes random classes, compiles them with the real processor and compiler, runs the
generated code, and compares each result with a reference model in the test that shares no code with
the generator: 48 `@Bitpack` layouts drawn from every supported type, and 40 `@FastDiff` classes of up to
40 properties, so both `Int` and `Long` masks are covered. Breaking the generator on purpose (dropping the
unsigned range check, the null code of nullable enums, sign extension, deep array equality, alias
expansion or unsigned array equality) makes it fail.

```sh
./gradlew --no-daemon build
./gradlew --no-daemon :processor:test --tests 'dev.aetherx86.boron.processor.DifferentialTest'
./gradlew --no-daemon :sample:test --tests 'dev.aetherx86.boron.sample.TypesAndNestingTest'
```

## Versioning

A bug or security fix increments `0.0.X`. New functionality or new platform support increments
`0.X.0`. See [CHANGELOG.md](CHANGELOG.md).

## License

MIT. See [LICENSE](LICENSE).

## Limitations

- **`@Bitpack` width**: `@Bitpack` packs properties into a single 64-bit `Long` value class, so the sum of declared bit widths across packed properties cannot exceed 64 bits.
- **`@FastDiff` property count**: `@FastDiff` represents changed properties as a 64-bit bitmask (`Long`), supporting up to 64 tracked properties per annotated class.
