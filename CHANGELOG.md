# Changelog

Versions follow this rule: a bug or security fix increments `0.0.X`, and new functionality
or new platform support increments `0.X.0`.

## 0.1.0

First release.

- `@FastDiff` generates a zero-allocation bit mask of the properties that differ between two
  instances, plus named constants and `applyDiff` for data classes.
- `@Bitpack` generates a value class that stores a class's properties packed into one `Long`.
- Clear compile errors at the source of a problem, and incremental, multi-round processing.
