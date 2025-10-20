# openzl-jni

[![CI](https://github.com/hybledav/openzl-jni/actions/workflows/ci.yml/badge.svg)](https://github.com/hybledav/openzl-jni/actions/workflows/ci.yml)
[![License: BSD-3-Clause](https://img.shields.io/badge/license-BSD--3--Clause-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-ff69b4.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.hybledav/openzl-jni.svg)](https://central.sonatype.com/artifact/io.github.hybledav/openzl-jni)

> Meta’s OpenZL compression engine for the JVM — zero-copy JNI bindings, pooled buffers.

---

## Getting Started

1. **Add the dependency**

     Always declare the base artifact (pure Java façade) **and** the classifier that matches your runtime so Maven pulls in both the APIs and the platform-specific native library.

     ```xml
     <dependency>
         <groupId>io.github.hybledav</groupId>
         <artifactId>openzl-jni</artifactId>
         <version>VERSION</version>
     </dependency>

     <dependency>
         <groupId>io.github.hybledav</groupId>
         <artifactId>openzl-jni</artifactId>
         <version>VERSION</version>
         <classifier>linux_amd64</classifier>
     </dependency>
     ```

     Replace `VERSION` with the release shown on [Maven Central](https://central.sonatype.com/artifact/io.github.hybledav/openzl-jni).

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

## OpenZL JNI — Java bindings for OpenZL compression

Java JNI bindings for the OpenZL native compression engine. This project exposes a small, intentionally thin Java API that lets Java applications use OpenZL compressors and graphs, compile SDDL descriptions, and run the training utilities provided by OpenZL.

Key features:

- Zero-copy I/O: direct ByteBuffer support for avoiding extra heap copies when compressing and decompressing.
- Buffer reuse: `OpenZLBufferManager` provides pooled buffers for common allocation patterns.
- Compression profiles: access built-in profiles, select graphs, and serialize/deserialize compressors.
- SDDL support: compile SDDL programs to bytecode and configure compressors to parse structured payloads.
- Training bridge: a minimal, directory-based JNI wrapper to run OpenZL training and return serialized candidate compressors.
- Native artifacts: Maven classifier artifacts include platform-specific native libraries (e.g. `linux_amd64`, `macos_arm64`).
- Buildability: native components are built with CMake; Maven integration is opt-in via `-Dnative.build=true`.

---

## Quick Usage

```java
var payload = "example".getBytes(java.nio.charset.StandardCharsets.UTF_8);

// Byte arrays
try (var compressor = new OpenZLCompressor()) {
    byte[] compressed = compressor.compress(payload);
    byte[] restored = compressor.decompress(compressed);
}

// Direct buffers
try (var compressor = new OpenZLCompressor()) {
    var src = java.nio.ByteBuffer.allocateDirect(payload.length);
    src.put(payload).flip();
    var dst = java.nio.ByteBuffer.allocateDirect(OpenZLCompressor.maxCompressedSize(payload.length));
    compressor.compress(src, dst);
}

// Buffer pooling
try (var buffers = OpenZLBufferManager.builder().build();
     var compressor = new OpenZLCompressor()) {
    var src = buffers.acquire(payload.length);
    src.put(payload).flip();
    var compressed = compressor.compress(src, buffers);
    var restored = compressor.decompress(compressed, buffers);
    buffers.release(src);
    buffers.release(compressed);
    buffers.release(restored);
}
```

`OpenZLCompressor.maxCompressedSize(int)` exposes `ZL_compressBound`, handy when sizing direct buffers.

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

byte[] serialCompressed;
try (OpenZLCompressor serial = new OpenZLCompressor()) {
    serial.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
    serialCompressed = serial.compress(payload);
}

byte[] sddlCompressed;
try (OpenZLCompressor sddl = new OpenZLCompressor()) {
    sddl.configureSddl(compiled);
    sddlCompressed = sddl.compress(payload);
}

System.out.printf("SERIAL=%d bytes, SDDL=%d bytes%n", serialCompressed.length, sddlCompressed.length);
```

Capture both outputs and choose the one that meets your size or latency goals; the best option depends on the data set.

---

## Compression Profiles & Levels

The Java façade exposes OpenZL’s curated graph presets through the `OpenZLProfile` enum. Load a profile at any time with `configureProfile` (optionally supplying profile-specific arguments).

```java
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    compressor.configureProfile(OpenZLProfile.CSV);
    compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_12);

    byte[] compressed = compressor.compress(payload);
    byte[] restored = compressor.decompress(compressed);
}
```

### Compression levels

`OpenZLCompressionLevel` enumerates the native `compressionLevel` parameter (values 1–22). Higher levels favour density at the cost of CPU; lower levels bias speed. A compressor starts at the engine’s default level, which you can read back using `getCompressionLevel()`.

```java
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    OpenZLCompressionLevel initial = compressor.getCompressionLevel();

    compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_22);
    byte[] high = compressor.compress(payload);

    compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);
    byte[] fast = compressor.compress(payload);

    // Frames created at the higher level remain fully compatible with lower-level decompressors.
    try (OpenZLCompressor reader = new OpenZLCompressor()) {
        reader.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);
        byte[] restored = reader.decompress(high);
        assert java.util.Arrays.equals(restored, payload);
    }
}
```

All level changes are sticky until you call `reset()` or explicitly select a new value, regardless of the active profile.

---

## Training compressors from samples

```java
import java.nio.file.Files;
import java.nio.file.Path;

Path tmp = Files.createTempDirectory("openzl-train");
try {
    Files.write(tmp.resolve("0"), "col1,col2\n1,2\n".getBytes());
    Files.write(tmp.resolve("1"), "col1,col2\n3,4\n".getBytes());
    TrainOptions opts = new TrainOptions();
    opts.maxTimeSecs = 2;
    byte[][] candidates = OpenZLCompressor.trainFromDirectory("csv", tmp.toString(), opts);
    if (candidates != null && candidates.length > 0) {
        Files.write(Path.of("trained-candidate-0.bin"), candidates[0]);
    }
} finally {
    for (Path p : Files.newDirectoryStream(tmp)) Files.deleteIfExists(p);
    Files.deleteIfExists(tmp);
}
```

## Planned features

See [TODO.md](TODO.md) for planned features and improvements.

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

## License & Attribution

The JNI wrapper is BSD-3-Clause like the upstream project. This repository is not affiliated with Meta Platforms. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) for details.
