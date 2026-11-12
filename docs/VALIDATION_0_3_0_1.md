# OpenZL JNI 0.3.0-1 validation

## Final release verification

Release code commit: `b0b3d06e89848e1dd8c5dc6947bee0d457240e25`.
The [final CI run](https://github.com/hybledav/openzl-jni/actions/runs/43245878428)
completed successfully on 2026-11-12. Linux x86_64, macOS ARM64, Windows
x86_64 and coverage all passed. Publishing was skipped.

| Final check | Result |
| --- | --- |
| Unit tests with `-Xcheck:jni`, each of the three platforms | 209 passed per platform; zero failures/errors/skips |
| Boundary/corruption/dictionary stress, each platform's finished JARs | 8,000 iterations per platform, 24,000 total; all five phases passed |
| Downloaded final Linux JAR, Java 11 / 17 / 21 / 25 | 40,000 stress iterations per runtime, 160,000 total; all passed |
| Final Linux binary, complete unit suite on Java 11 | 209 passed with JNI checking; zero failures/errors/skips |
| Final native sources rebuilt with ASan/UBSan | 209 unit tests and 40,000 stress iterations passed; no sanitizer/JNI findings |
| Independent audit of all three downloaded CI packages | Passed base/source/Javadoc/platform licenses, native contents, unit XML reports and stress result JSON |
| CI artifact provenance | All six package/diagnostic ZIP digests matched GitHub's recorded SHA-256 values |
| Full release assembly using the final CI binaries | Maven package and `exec:exec@verify-release` passed for all six JARs; signing/upload not run |
| License inventory | 24 unique notice/license entries; pinned source checks passed on all three platforms |

The final stress total is **224,000 iterations** (24,000 platform CI +
160,000 runtime matrix + 40,000 sanitizer), separate from earlier runs below.
The sanitizer unit-test process was observed loading the exact rebuilt native
library by SHA-256 (`6505a00f660e9776b083e0477ca3bb0f564ad9e236bbe4fa1e1200ede745f1d9`)
with the ASan runtime present. This prevents accidentally treating an ordinary
resource-bundled library as the instrumented test build. Sanitizer limitations
are listed below.

The license audit fixed nested notices missing from the earlier inventory.
Only the final run's artifacts, with these corrections, are release candidates.
Per-platform JAR checksums and machine-readable results are recorded in
`final_verified_release` in [`VALIDATION_0_3_0_1.json`](VALIDATION_0_3_0_1.json).
Those checksums identify downloaded CI artifacts, not a future signed publication.

## Earlier validation history

Validated locally on Linux x86_64 on 2026-11-12. Implementation commit: `404da15`.
The subsequent [cross-platform CI baseline](https://github.com/hybledav/openzl-jni/actions/runs/43224524001)
at `dde0584` passed Linux x86_64, macOS ARM64, Windows x86_64 and coverage before
the redundant shim cleanup began. The local stress/sanitizer results below
are separate from those platform jobs.

The [post-cleanup CI run](https://github.com/hybledav/openzl-jni/actions/runs/43231690262)
at `71a252c` also passed all three platforms and coverage: **209 tests per
platform, zero failures/errors/skips**. Its Java coverage report records 80.75%
line, 69.86% branch and 82.08% method coverage. This measures Java coverage,
not native-code coverage.

## Final packaging audit

The finished Linux, macOS and Windows artifacts from that post-cleanup run
were downloaded and inspected independently. All have their expected 64-bit
architecture. Windows includes checksummed GCC/MinGW-w64/winpthreads supplier
notices, including the GCC Runtime Library Exception. Its DLL imports only
Windows system libraries. Linux uses the system GCC/C++/C runtime; macOS uses
the system C++/C/zlib libraries.

A review of compiler dependency files found missing nested source notices
despite the earlier inventory checks passing. The corrected inventory now
includes divsufsort (MIT, within Zstandard), LZ4's separate xxHash copy,
LZ4 block/frame/file attributions, and Zstandard's threading attribution.
Their full texts are extracted from the exact pinned build sources and
checksummed. Protobuf's upb runtime is covered by its existing BSD license.
The unused nlohmann JSON research/test header is not linked into this library.
Supplemental GSL and sweater licenses now use immutable upstream commits and
are fetched and checked during source validation. Packaged wrapper LICENSE
and NOTICE must match the repository copies.

The final release gate enables `-Xcheck:jni` for all platform unit suites and
runs the boundary/corruption/dictionary stress harness on Linux, macOS and
Windows. CI retains source and Javadoc JARs, unit-test XML reports and
per-platform stress logs alongside the tested binaries. Finalized artifacts
must pass the expanded license check; the pre-audit packages are superseded.

The downloaded post-cleanup Linux binary also passed another **160,000 stress
iterations** across Java 11/17/21/25 (40,000 per runtime). Repackaging those
exact CI binaries with the corrected notices passed the full Maven reactor,
209 unit tests with JNI checking, and all six archive checks. Negative checks
confirmed that missing or altered nested license texts are rejected.

| Check | Result |
| --- | --- |
| Fresh native Release build in an isolated checkout | Passed; no previous build products reused |
| Complete Maven reactor build/package, Java 21, `-Xcheck:jni` | 209 tests, zero failures/errors/skips |
| Complete unit suite on Java 11.0.33, `-Xcheck:jni` | 209 tests, zero failures/errors/skips |
| Complete unit suite with ASan/UBSan native build, Java 21 | 209 tests, zero failures/errors/skips; no sanitizer findings |
| Java 11 / 17 / 21 / 25 stress runs | 40,000 iterations per runtime; all passed |
| ASan/UBSan stress run | 40,000 iterations; all passed |
| Finished JARs, independent old/new JVMs | Format-24 byte/direct/numeric exchange, old trained graph loading and new trained byte/Protobuf frames passed |
| Dictionary persistence across JVMs | Passed |
| Source provenance, licenses, base/source/Javadoc/Linux archives | Passed |
| All-platform release gate with missing macOS/Windows binaries | Correctly rejected incomplete local artifacts |

The stress total is **200,000 iterations**, excluding boundary probes and earlier
smoke runs. Each variant runs 10,000 array/direct round trips, 10,000 typed numeric
cases, 10,000 corrupted-frame cases, and 10,000 trained-dictionary cases using
eight workers with independent contexts. Seeds and artifact hashes are in
[`VALIDATION_0_3_0_1.json`](VALIDATION_0_3_0_1.json).

Coverage includes empty and boundary-sized inputs, nonzero offsets, read-only
sources, output sentinels, formats 24/27, compression levels, graph reloads,
IEEE float/double bit patterns, resets, pooling, truncated/bit-flipped graphs
and bundles, and recovery after failed operations. The corrupted-frame corpus
contains Zstandard, generic, store, Huffman, FSE, entropy, LZ and numeric
Transformer frames. Corrupted data is decoded into bounded destinations;
typed convenience calls are limited to inspected sizes of at most 128 KiB.

Validation found and fixed two boundary defects:

- Read-only direct output buffers were written through. Compression,
  decompression and Protobuf output now reject them before writing.
- Overlapping input/output ranges were accepted. Array regions and aliased
  direct views now fail before writing; disjoint regions of the same allocation
  and empty input ranges still work.

The new regressions reproduced failures before the fixes. Six guard tests now
cover rejection, unchanged data/cursors and permitted disjoint regions.

After that green CI baseline, the two forced standard-library include shims
and `fix_future_capture.py` were removed with their CMake/CI hooks. OpenZL
0.3.0 already contains those fixes; all 11 affected logger/I/O source files
also compiled locally without injected headers. The Windows import helpers,
Protobuf target-format patch and optional sanitizer workaround remain.

The first CI run exposed a baseline provenance mistake: the local Maven cache's
`0.2.1` JAR contained a format-24 native build, while Maven Central's `0.2.1`
writes format 21. The compatibility check now uses freshly downloaded public
`0.2.0-1` JARs, with SHA-256 values pinned in
`scripts/compatibility/baseline.json`. The corrected check was rerun locally;
a cached or substituted baseline is rejected before launching either JVM.

## Reproducing the stress run

Use a JDK, the finished base/native JARs, and a separate directory for each result:

```sh
python3 scripts/validate_native.py \
  --base-jar JNI/openzl-jni/target/openzl-jni-0.3.0-1.jar \
  --native-jar JNI/openzl-jni/target/openzl-jni-0.3.0-1-linux_amd64.jar \
  --count 10000 --seed 310024 --output .cache/native-validation/repeated
```

Select another runtime with `--java /path/to/jdk/bin/java`. The harness compiles
its probe for Java 11 and runs each phase in an isolated JVM with native-call
checks, bounded Java/direct memory and a timeout. It writes phase logs and a
machine-readable result. Each platform CI job runs the same command with
2,000 iterations per phase and retains diagnostics.

## Sanitizer scope and reproduction

The test build used GCC 14.2 and
`-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer`.
Configure a separate build directory with those C/C++ flags and
`-DCMAKE_SHARED_LINKER_FLAGS=-fsanitize=address,undefined`.
For this GCC/Abseil combination, also pass
`-DCMAKE_PROJECT_INCLUDE="$PWD/scripts/validation/gcc_sanitizer_workaround.cmake"`.

The workaround disables UBSan on `flags_reflection`, `libprotobuf` and
`libprotoc`, retaining ASan there. OpenZL and the JNI bridge retain both.
This avoids the reproduced [Abseil/GCC constexpr compilation issue](https://github.com/abseil/abseil-cpp/issues/1634).
XGBoost/dmlc's external build and assembly kernels are outside full sanitizer
instrumentation. LeakSanitizer was disabled for the JVM; these results do not
certify absence of leaks or data races.

Use the instrumented library directly, without a release native JAR:

```sh
python3 scripts/validate_native.py \
  --base-jar JNI/openzl-jni/target/openzl-jni-0.3.0-1.jar \
  --native-dir cmake_sanitize/cli \
  --asan-runtime "$(g++ -print-file-name=libasan.so)" \
  --cxx-runtime "$(g++ -print-file-name=libstdc++.so)" \
  --count 10000 --seed 9310024 --output .cache/native-validation/sanitizers
```

The harness preloads ASan and libstdc++ so native exceptions are intercepted
correctly, and leaves HotSpot's signal handling available. Only the exercised
paths are covered. Detailed local logs are retained under
`.cache/native-validation/records` and the individual variant directories.
