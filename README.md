# openzl-jni

Unofficial Java bindings for Meta’s [OpenZL](https://facebook.github.io/openzl/) compressor.
The goal is identical to the [zstd-jni](https://github.com/luben/zstd-jni) project:
ship a single jar that lets JVM applications use the native encoder/decoder without
building the C++ code themselves.

> **Status:** 0.1.1 – Linux x86_64 binary bundled. Contributions adding other
> platforms are welcome.

---

## Coordinates

```xml
<dependency>
  <groupId>io.github.hybledav</groupId>
  <artifactId>openzl-jni</artifactId>
  <version>0.1.1</version>
  <classifier>linux_amd64</classifier>
</dependency>
```

- Published on Maven Central; artifacts are GPG-signed so Maven Central users can
  verify integrity.
- The native `libopenzl_jni.so` is published as a classifier jar (`linux_amd64`).
  The main jar bundles the JNI classes; the classifier provides the shared
  library. If you ship your own build, drop it on `java.library.path` and the code
  falls back to `System.loadLibrary("openzl_jni")`.

## Usage

```java
import io.github.hybledav.OpenZLCompressor;

byte[] payload = ...;
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    byte[] compressed = compressor.compress(payload);
    byte[] restored   = compressor.decompress(compressed);
    // verify the round‑trip
}
```

## Building the JNI module

```bash
# build the native library
cmake -S . -B cmake_build -DCMAKE_BUILD_TYPE=Release
cmake --build cmake_build --target openzl_jni

# bundle it into the jar
cp cmake_build/cli/libopenzl_jni.so JNI/openzl-jni/src/main/resources/lib/linux_amd64/
mvn -pl JNI/openzl-jni -am clean package
```

Releases are currently produced with GCC 14.2.0 on Debian. If you publish your own,
rebuild the `.so` with your toolchain before cutting a release.

## Testing

`JNI/openzl-jni/src/test/java/io/github/hybledav/TestCompress500MB.java` performs a
500 MB pseudo-random round‑trip as part of `mvn test`.

## Releasing

```bash
mvn -f JNI/pom.xml -pl openzl-jni -am clean package \
    org.sonatype.central:central-publishing-maven-plugin:publish
```

Then release the staging repository in the Sonatype portal.

## License & attribution

The JNI wrapper is BSD-3-Clause like the upstream project. This repository is not
affiliated with Meta Platforms. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for details.
