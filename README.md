# openzl-jni

[![CI](https://github.com/hybledav/openzl-jni/actions/workflows/ci.yml/badge.svg)](https://github.com/hybledav/openzl-jni/actions/workflows/ci.yml)
[![License: BSD-3-Clause](https://img.shields.io/badge/license-BSD--3--Clause-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-ff69b4.svg)](https://openjdk.org/projects/jdk/21/)

> Meta’s OpenZL compression engine for the JVM — zero-copy JNI bindings, pooled buffers.

---

## Getting Started

1. **Add the dependency**

     Always declare the base artifact (pure Java façade) **and** the classifier that matches your runtime so Maven pulls in both the APIs and the platform-specific native library.

     ```xml
     <dependency>
         <groupId>io.github.hybledav</groupId>
         <artifactId>openzl-jni</artifactId>
         <version>0.1.6</version>
     </dependency>

     <dependency>
         <groupId>io.github.hybledav</groupId>
         <artifactId>openzl-jni</artifactId>
         <version>0.1.6</version>
         <classifier>linux_amd64</classifier>
     </dependency>
     ```

     Swap `linux_amd64` for the classifier that matches your target:

     - `linux_amd64`
     - `macos_arm64`
     - `windows_amd64`

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
- **Multi-platform CI** – GitHub Actions build classifier jars for Linux (x86_64), macOS (arm64), and Windows (x86_64).
- **Self-contained build** – produces Maven-style jars and classifier artifacts ready to consume directly in your projects.

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

## Structured Data with SDDL

OpenZL ships a Simple Data Description Language (SDDL) graph that can parse structured payloads before handing them to the clustering engine. The JNI bindings let you compile an SDDL program once, cache the bytecode, and reuse it across many compressions. For an in-depth language guide, see the [official docs](https://openzl.org/api/c/graphs/sddl/).

```java
import io.github.hybledav.OpenZLCompressor;
import io.github.hybledav.OpenZLGraph;
import io.github.hybledav.OpenZLSddl;

String rowStreamSddl = String.join("\n",
        "field_width = 4;",
        "Field1 = Byte[field_width];",
        "Field2 = Byte[field_width];",
        "Row = {",
        "  Field1;",
        "  Field2;",
        "};",
        "row_width = sizeof Row;",
        "input_size = _rem;",
        "row_count = input_size / row_width;",
        "expect input_size % row_width == 0;",
        "RowArray = Row[row_count];",
        ": RowArray;");

byte[] compiled = OpenZLSddl.compile(rowStreamSddl, true, 0);
byte[] payload = "12345678".repeat(256).getBytes(java.nio.charset.StandardCharsets.US_ASCII);

byte[] storeCompressed;
try (OpenZLCompressor store = new OpenZLCompressor(OpenZLGraph.STORE)) {
    storeCompressed = store.compress(payload);
}

byte[] sddlCompressed;
try (OpenZLCompressor sddl = new OpenZLCompressor()) {
    sddl.configureSddl(compiled);
    sddlCompressed = sddl.compress(payload);
}

System.out.printf("STORE=%d bytes, SDDL=%d bytes%n", storeCompressed.length, sddlCompressed.length);
```

Because the SDDL program understands the row layout, the OpenZL engine can cluster repeated fields more effectively—typically producing smaller frames than the generic STORE graph for structured datasets.

---

## Usage Scenarios

- **Analytics pipelines** – compress structured telemetry and numeric streams without dropping to native code.
- **Data science workloads** – feed large numeric arrays into the NUMERIC graph for superior ratios.
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

---

## Testing

`JNI/openzl-jni/src/test/java/io/github/hybledav/TestCompress500MB.java` performs a 500 MB pseudo-random round-trip as part of `mvn test`. Additional tests cover numeric compression helpers and metadata inspection.

---

## License & Attribution

The JNI wrapper is BSD-3-Clause like the upstream project. This repository is not affiliated with Meta Platforms. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for details.
