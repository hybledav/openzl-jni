# OpenZL JNI 0.3.0-2 release validation

Published on Maven Central on 2026-11-14:
[base and all platform artifacts](https://repo.maven.apache.org/maven2/io/github/hybledav/openzl-jni/0.3.0-2/).
Upstream remains OpenZL v0.3.0 (`32246b48faee46807f84183dac4db479089f5445`).

Release source: `e49497c99ca144c5b2604aba300170ff623d2285`.
The feature commit is `a1ec4b0506ec1fe6f7d5a2a54111d61421c8619a`; the following
commit updates the sitemap. The [publishing workflow](https://github.com/hybledav/openzl-jni/actions/runs/43620960659)
completed successfully, including the publish/upload job.

## Release checks

| Platform | Java | Tests | Failures/errors/skips | License checks |
|---|---|---:|---|---|
| Linux x86_64 | 21 | 245 | 0 / 0 / 0 | Source and archives passed |
| macOS ARM64 | 21 | 245 | 0 / 0 / 0 | Source and archives passed |
| Windows x86_64 | 21 | 245 | 0 / 0 / 0 | Source, runtime notices and archives passed |

All three platforms passed native boundary/corruption stress checks. Linux also
passed the published-baseline frame compatibility check; that CI step is skipped
on macOS and Windows. The separate JaCoCo coverage job passed.
No new coverage gate was added.

Sonatype deployment `b68e0722-a6a5-4162-8461-b3814d4a3d89` reached `VALIDATED`
with exactly `pkg:maven/io.github.hybledav/openzl-jni@0.3.0-2`. Before publishing:

- All six staged JARs passed archive/license verification, including sources,
  Javadoc and all three native classifiers; the release POM version was checked.
- The staged Linux binaries passed 100 managed round trips, mixed typed-stream
  compression/decompression and configured-LZ compression on Java 25, with JNI
  checking and illegal native access denied unless explicitly enabled.
- Managed gRPC Compression commit `6dea908` passed its full **48-test** reactor
  suite against those exact staged artifacts on Java 21 with `-Xcheck:jni`.

The deployment was then published through Sonatype's Publisher API and reached
`PUBLISHED`. All seven primary files (six JARs and the POM) were downloaded from Maven Central and matched
that tested staged bundle by SHA-256. Public JAR license checks passed again.
The public Linux binaries passed the same smoke test on Java 11 and Java 25.
The base JAR contains only Java 11 class-file versions (55). The local Maven
cache was refreshed with these release binaries; locally built candidate files
were backed up.

These smoke checks validate the downloaded Linux artifact; native Java 25 tests
on macOS and Windows were not performed. The local sanitizer results and their
limits are recorded in [the Java 25 guide](JAVA_25.md) and
[managed buffer validation](MANAGED_COMPRESSION.md).

## Public artifact SHA-256

```text
87cafcd16eda6a67a8e1f661c8930849732b808b1fa5b302b111265e89261b43  openzl-jni-0.3.0-2.pom
0453198bd25ec6bdc167dc95a23450ee244d5403182433a5fedd7dc49f12a71a  openzl-jni-0.3.0-2.jar
9a1631e7570847e23049425bf7a7e73e09570aa49e8c4e2ae058f2ed3fbbb2de  openzl-jni-0.3.0-2-sources.jar
5cb3e3ccd8c5529ce1ea650e6fc681226f3d841eb0584c7fd296eee0e828a0a8  openzl-jni-0.3.0-2-javadoc.jar
6515a882f5a6a3a6942f652291d9b53cd7644d36f31e767a06d89a488c873fad  openzl-jni-0.3.0-2-linux_amd64.jar
5b9b38757aafc2012060c90eb5347bc81e88f09a5a10cf8e597475b639347dc0  openzl-jni-0.3.0-2-macos_arm64.jar
5d7d741f012cdae9d9f80471a3044aded57c4788fc795ec02fef6e38bc089a08  openzl-jni-0.3.0-2-windows_amd64.jar
```

Full workflow metadata, artifact manifests and consumer logs are retained locally
under `.cache/release032/`. No credentials are stored in the release evidence.
