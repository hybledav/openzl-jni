# openzl-jni

[![CI](https://github.com/hybledav/openzl-jni/actions/workflows/ci.yml/badge.svg)](https://github.com/hybledav/openzl-jni/actions/workflows/ci.yml)
[![License: BSD-3-Clause](https://img.shields.io/badge/license-BSD--3--Clause-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-ff69b4.svg)](https://openjdk.org/projects/jdk/21/)

> Meta’s OpenZL compression engine for the JVM — zero-copy JNI bindings, pooled buffers, and multi-platform native binaries ready for production pipelines.

---

## Getting Started

1. **Add the dependency**

   ```xml
   <dependency>
     <groupId>io.github.hybledav</groupId>
     <artifactId>openzl-jni</artifactId>
     <version>0.1.2</version>
     <classifier>linux_amd64</classifier>
   </dependency>
   ```

   Classifier jars package the native library per platform (e.g. `linux_amd64`, `macos_x86_64`, `windows_amd64`). The main artifact ships the Java façade.

2. **Compress data**

   ```java
   import io.github.hybledav.OpenZLCompressor;

   byte[] payload = ...;
   try (OpenZLCompressor compressor = new OpenZLCompressor()) {
       byte[] compressed = compressor.compress(payload);
       byte[] restored   = compressor.decompress(compressed);
   }
   ```

3. **Select graphs for specialised payloads**

   ```java
   int[] measurements = ...;

   try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
       byte[] compressed = compressor.compressInts(measurements);
       int[] restored = compressor.decompressInts(compressed);

       OpenZLCompressionInfo info = compressor.inspect(compressed);
       System.out.printf("compressed=%d bytes, flavor=%s, graph=%s%n",
               info.compressedSize(), info.flavor(), info.graph());
   }
   ```

---

## Why openzl-jni?

- **Performance-first JNI** – zero-copy direct buffer support and reusable native contexts.
- **Buffer pooling built in** – `OpenZLBufferManager` keeps hot paths off-heap without churn.
- **Compression intelligence** – inspect frames for inferred graph, format version, and element counts before decoding.
- **Multi-platform CI** – GitHub Actions build classifier jars for Linux (x64/ARM), macOS (x64/ARM), and Windows.
- **Drop-in publishing** – artifacts are ready to be staged to Maven Central or GitHub Packages as part of your release train.

---

## Quick Usage

```java
import io.github.hybledav.OpenZLBufferManager;
import io.github.hybledav.OpenZLCompressor;

byte[] payload = ...;

// Byte array round-trip
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    byte[] compressed = compressor.compress(payload);
    byte[] restored   = compressor.decompress(compressed);
}

// Direct buffers for zero-copy paths
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    var src = java.nio.ByteBuffer.allocateDirect(payload.length);
    src.put(payload).flip();
    var dst = java.nio.ByteBuffer.allocateDirect(payload.length + 4096);
    compressor.compress(src, dst);
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

`OpenZLCompressor.maxCompressedSize(inputLength)` exposes `ZL_compressBound`, handy when sizing pre-allocated buffers.

---

## Usage Scenarios

- **Analytics pipelines** – compress structured telemetry and numeric streams without dropping to native code.
- **Data science workloads** – feed large numeric arrays into the NUMERIC graph for superior ratios.
- **Edge services** – bundle platform-specific classifier jars to run OpenZL across Linux, macOS, and Windows fleets.
- **Storage systems** – inspect frames ahead of time to choose storage tiers or validate payloads before commit.

---

## Building from Source

```bash
# build the native library
cmake -S . -B cmake_build -DCMAKE_BUILD_TYPE=Release
cmake --build cmake_build --target openzl_jni

# bundle it into the jar
mkdir -p JNI/openzl-jni/src/main/resources/lib/linux_amd64
cp cmake_build/cli/libopenzl_jni.so JNI/openzl-jni/src/main/resources/lib/linux_amd64/
mvn -f JNI/pom.xml -pl openzl-jni -am clean package
```

Releases are currently produced with GCC 14.2.0 on Debian. If you publish your own build, rebuild the shared library with your toolchain first.

---

## Testing

`JNI/openzl-jni/src/test/java/io/github/hybledav/TestCompress500MB.java` performs a 500 MB pseudo-random round-trip as part of `mvn test`. Additional tests cover numeric compression helpers and metadata inspection.

---

## License & Attribution

The JNI wrapper is BSD-3-Clause like the upstream project. This repository is not affiliated with Meta Platforms. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for details.
