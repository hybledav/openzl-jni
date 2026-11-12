# OpenZL JNI 0.3.0-1 validation

Validated on Linux x86_64 on 2026-11-12. Implementation commit: `404da15`.
The local Linux checks pass. macOS ARM64 and Windows still require their
real build/test jobs; nothing was pushed or dispatched to GitHub.

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
machine-readable result. The future Linux CI job runs the same command with
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
