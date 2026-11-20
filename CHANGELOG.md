# Changelog

Versions follow this rule: a bug or security fix increments `0.0.X`, and new functionality
or new platform support increments `0.X.0`.

## [0.2.0] - 2026-11-20

### Added

- `@Bitpack` supports `Char`, `UByte`, `UShort`, `UInt` and `ULong` (width set with `@Bits`, range
  checked as unsigned), `Float` and `Double` (stored as their exact 32 or 64 raw IEEE 754 bits, NaN
  payloads and `-0.0` included), and nullable enum classes (code 0 is null, each entry is its ordinal
  plus one, so the default width grows by a code when needed).
- `@Bitpack` classes can contain other `@Bitpack` classes from the same module. A nested property is
  embedded as its packed bits, defaults to the nested class's width and may be given more with `@Bits`
  as room to grow. A nested property `inner` also gets an `innerPacked` getter and a `withInner`
  overload taking the packed form, and neither builds an object. Nesting works to any depth.
- Compile errors at the offending property for: a `@Bitpack` class that contains itself (the message
  shows the loop, such as `A -> B -> A`), a nested class that cannot be packed (with its own reason), a
  public class with an internal nested type, a nested property narrower than its type, private or
  protected `@Bitpack` properties and constructors, a `@Bitpack` property named `raw` or whose getter or
  `with` function clashes with another's, two `@FastDiff` properties that map to one constant name (such
  as `fooBar` and `foo_bar`) and a `@FastDiff` property named `all`, which would clash with `ALL`.
- A differential test compiles 48 random `@Bitpack` layouts and 40 random `@FastDiff` classes with the
  real processor and compiler, runs the generated code and compares every packed value, getter, `with`
  result, range check, mask and `applyDiff` result with an independent reference model.

### Fixed

- `@FastDiff` followed type aliases only by name, so a property typed `typealias Celsius = Double`
  reported NaN as changed on every comparison and an alias of `IntArray` compared by identity. Aliases
  are now expanded first.
- `@FastDiff` compared an `Array` of arrays, such as `Array<IntArray>`, by the identity of the inner
  arrays. `Array` properties now use `contentDeepEquals`.
- `@FastDiff` compared `UByteArray`, `UShortArray`, `UIntArray` and `ULongArray` by identity. They now
  compare by contents, and the generated functions opt in to `ExperimentalUnsignedTypes`.

## 0.1.0

First release.

- `@FastDiff` generates a zero-allocation bit mask of the properties that differ between two
  instances, plus named constants and `applyDiff` for data classes.
- `@Bitpack` generates a value class that stores a class's properties packed into one `Long`.
- Clear compile errors at the source of a problem, and incremental, multi-round processing.
