# openzl-jni

Unofficial Java bindings for Meta’s [OpenZL](https://facebook.github.io/openzl/) compressor.
The goal is identical to the [zstd-jni](https://github.com/luben/zstd-jni) project:
ship a single jar that lets JVM applications use the native encoder/decoder without
building the C++ code themselves.

> **Status:** 0.1.2 – Linux x86_64 binary bundled. Contributions adding other
> platforms are welcome.

---

## Coordinates

```xml
<dependency>
  <groupId>io.github.hybledav</groupId>
  <artifactId>openzl-jni</artifactId>
  <version>0.1.2</version>
  <classifier>linux_amd64</classifier>
</dependency>
```

- Published on Maven Central; artifacts are GPG-signed so Maven Central users can
  verify integrity.
- The native `libopenzl_jni.so` is published as a classifier jar (`linux_amd64`).
  The main jar bundles the JNI classes; the classifier provides the shared
  library. If you ship your own build, drop it on `java.library.path` and the code
  falls back to `System.loadLibrary("openzl_jni")`.
- `OpenZLBufferManager` hands out pooled direct `ByteBuffer`s so you can stay
  off-heap without fighting JVM allocation churn.

## Usage

```java
import io.github.hybledav.OpenZLCompressor;

byte[] payload = ...;
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    byte[] compressed = compressor.compress(payload);
    byte[] restored   = compressor.decompress(compressed);
    // verify the round‑trip
}

// Direct buffers for zero-copy performance
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    var src = java.nio.ByteBuffer.allocateDirect(payload.length);
    src.put(payload).flip();
    var compressed = java.nio.ByteBuffer.allocateDirect(payload.length + 4096);
    compressor.compress(src, compressed);
}

// Reusable buffer manager
try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build();
        OpenZLCompressor compressor = new OpenZLCompressor()) {
    var src = buffers.acquire(payload.length);
    src.put(payload).flip();
    var compressed = compressor.compress(src, buffers);
    buffers.release(src);

    var restored = compressor.decompress(compressed, buffers);
    buffers.release(compressed);
    buffers.release(restored);
}
```

Call `release` once you're done with a buffer so the manager can recycle it.
`OpenZLCompressor.maxCompressedSize(inputLength)` exposes `ZL_compressBound`, and
`OpenZLBufferManager` now has `acquireForCompression`/`acquireForDecompression`
helpers for manual sizing when you want to preallocate buffers yourself.

## Building the JNI module

```bash
# build the native library
cmake -S . -B cmake_build -DCMAKE_BUILD_TYPE=Release
cmake --build cmake_build --target openzl_jni

# bundle it into the jar
mkdir -p JNI/openzl-jni/src/main/resources/lib/linux_amd64
cp cmake_build/cli/libopenzl_jni.so JNI/openzl-jni/src/main/resources/lib/linux_amd64/
mvn -pl JNI/openzl-jni -am clean package
```

Releases are currently produced with GCC 14.2.0 on Debian. If you publish your own,
rebuild the `.so` with your toolchain before cutting a release.

## Testing

`JNI/openzl-jni/src/test/java/io/github/hybledav/TestCompress500MB.java` performs a
500 MB pseudo-random round‑trip as part of `mvn test`.

## License & attribution

The JNI wrapper is BSD-3-Clause like the upstream project. This repository is not
affiliated with Meta Platforms. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for details.
