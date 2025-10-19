# OpenZL JNI Benchmarks

Standalone Maven module to benchmark OpenZL against `zstd-jni` on the Silesia corpus (SAO file).

This module is not published and depends on `zstd-jni` only in test scope. It is intended for local reference and profiling.

## What it runs
- `SilesiaSaoBenchmarkTest`: Compares OpenZL with profile `le-u64` vs Zstd level 3 on `sao`.
- `SilesiaSaoProfileBenchmarkTest`: Compares OpenZL with profile `sao` vs Zstd level 3 on `sao`.

## Prereqs
- Build the native library so the `openzl-jni` test artifact contains the platform resources.

```fish
mvn -f ../ -Dnative.build=true -DskipTests package
```

## Running the benchmarks
By default the tests will try to locate or download Silesia `silesia.zip`, extract the `sao` file to `target/silesia/sao`, and run.

You can provide the data explicitly to avoid network:

```fish
# Either provide the extracted file path
set -x SAO_PATH /path/to/silesia/sao

# Or provide the archive path (will be extracted)
set -x SILESIA_ZIP /path/to/silesia.zip
```

Run just the benchmark module tests:

```fish
mvn -f ../ -pl benchmark -am test
```

Or run a single test class:

```fish
mvn -f ../ -pl benchmark -Dtest=io.github.hybledav.benchmark.SilesiaSaoBenchmarkTest test
```

## Strict assertions
To make the tests assert that OpenZL is both faster and has a better compression ratio, set:

```fish
set -x BENCH_STRICT_ASSERT true
```

Note: On the textual Silesia `sao` file, the `le-u64` profile (meant for little-endian 64-bit unsigned integers) may not be the best fit and can underperform compared to Zstd level 3. The `sao` profile is a better match for this file and should yield significantly better ratios.

