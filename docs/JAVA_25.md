# Java 25 support (0.3.0-2)

The current local source builds and runs on Java 25. The minimum runtime remains
Java 11: all library classes produced by JDK 25 still use class-file version 55
through `--release 11`. No Java 25 language feature or preview API is required.
These changes target wrapper version `0.3.0-2`. Publication follows release validation.

## Build changes

- JaCoCo moves from 0.8.11 to 0.8.14, the first release with
  [official Java 25 support](https://www.jacoco.org/jacoco/trunk/doc/changes.html).
  The old agent could log unsupported class-file errors even when Maven reported
  a successful build. The existing CI coverage commands use the same version.
- JMH annotation processing is explicitly configured for test compilation.
  Newer JDKs require processors to be enabled explicitly; otherwise the Java
  tests can pass while `META-INF/BenchmarkList` is missing. See
  [Maven's processor configuration](https://maven.apache.org/components/plugins/maven-compiler-plugin-4.x/examples/annotation-processor.html).
- The JNI module uses a single `maven.compiler.release=11` setting instead of
  contradictory source/target 21 properties alongside release 11.
- A Maven profile activated on JDK 24+ grants native access to the class path
  and runs forked tests with `--illegal-native-access=deny`. The flags are absent
  on Java 11 and 21, and coverage arguments are retained. Tests also work with
  `-Djacoco.skip=true`.

No coverage gate or new CI job is included.
CI's JDK selection remains 21; the Java 25 results below are local Linux checks.

## Application launch

For applications using the class path:

```bash
java --enable-native-access=ALL-UNNAMED -cp "your-application-classpath" your.Main
```

To verify explicit native access, additionally use
`--illegal-native-access=deny`. These are launcher options, rather than a library
setting; applications control which code receives access. See the
[Java 25 launcher documentation](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html).
Do not pass the Java 25 options to a Java 11 JVM.

## Local validation, 2026-11-14

Tested on Linux x86_64 with Zulu OpenJDK **25.0.1+8-LTS**:

- Rebuilt the JNI library against JDK 25's headers.
- Clean full Maven reactor package: **236 tests**, no failures, errors or skips,
  with `-Xcheck:jni`, explicit native access and illegal native access denied.
- Clean Java 11 and Java 21 regression suites: **236 tests each**, passing with
  the updated build configuration and JDK 25-header native library.
- Java 25 ASan/UBSan suite: **236 tests**, passing. The loaded native library's
  hash and the ASan runtime were verified in the test JVM. This sanitizer binary
  retains the previously tested JNI headers; the normal build uses JDK 25 headers.
- **40,000 native stress iterations** on Java 25, plus boundary cases, pass with
  JNI checking and illegal native access denied.
- The four recently added Java interfaces and their compressor entry points
  retain **98/98 branches and 207/207 lines covered** under JaCoCo 0.8.14.
  This is Java coverage, not native C/C++ branch coverage.
- JMH generates all seven benchmark entries and a short `protoToZlArray` smoke
  run succeeds with native access denied unless explicitly granted. The smoke
  run is nonforked because the restricted environment blocks JMH's loopback
  socket; it is not a performance measurement.
- Coverage-disabled focused tests pass. The JDK 25-built JAR contains only
  Java 11 class versions. Base, sources, Javadoc and Linux classifier license
  checks pass; the native dependency inventory is unchanged.

Maven's Guava, Protobuf and JMH emit `sun.misc.Unsafe` deprecation notices on
Java 25. These notices remain visible; no instrumentation errors, JNI checking
diagnostics or sanitizer findings were observed after the fixes.

Sanitizer limitations are unchanged: UBSan is disabled for `flags_reflection`,
`libprotobuf` and `libprotoc` for the GCC/Abseil issue, with ASan retained.
External XGBoost/dmlc and assembly are not fully instrumented, and LeakSanitizer
is disabled for HotSpot. macOS and Windows Java 25 builds have not been tested.

Detailed local logs, coverage and artifact hashes are retained under
`.cache/java25-validation/`; the JDK 25-built JARs there are validation artifacts,
not a new published release.

Subsequent [managed compression testing](MANAGED_COMPRESSION.md) found and fixed
existing pool bugs on both Java 21 and 25. The expanded suite has 245 passing
tests on Java 11/21/25 and under Java 25 ASan/UBSan, including managed and
virtual-thread workloads with bounded direct memory.
