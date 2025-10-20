package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Documentation snippets must stay executable. Each test mirrors a code sample that
 * appears in the published docs so we can trust the examples stay up to date.
 */
class DocExamplesTest {

    @Test
    void quickStartByteArrays() {
        byte[] payload = "openzl-jni quick start".getBytes(StandardCharsets.UTF_8);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] compressed = compressor.compress(payload);
            byte[] restored = compressor.decompress(compressed);

            assertArrayEquals(payload, restored);

            OpenZLCompressionInfo info = compressor.inspect(compressed);
            assertEquals(OpenZLCompressionInfo.DataFlavor.SERIAL, info.flavor());
            assertEquals(payload.length, info.originalSize());
        }
    }

    @Test
    void zeroCopyWithBufferManager() {
        byte[] payload = "direct buffers keep JNI zero-copy".getBytes(StandardCharsets.UTF_8);

        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder()
                .minimumCapacity(1 << 12)
                .alignment(256)
                .build();
             OpenZLCompressor compressor = new OpenZLCompressor()) {

            ByteBuffer src = buffers.acquire(payload.length);
            src.put(payload).flip();

            ByteBuffer compressed = compressor.compress(src, buffers);
            ByteBuffer restored = compressor.decompress(compressed, buffers);

            byte[] roundTrip = new byte[restored.remaining()];
            restored.get(roundTrip);
            assertArrayEquals(payload, roundTrip);

            buffers.release(src);
            buffers.release(compressed);
            buffers.release(restored);
        }
    }

    @Test
    void numericArraysGraph() {
        int[] readings = new int[1024];
        for (int i = 0; i < readings.length; i++) {
            readings[i] = (i % 17) * 42;
        }

        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressed = compressor.compressInts(readings);
            int[] restored = compressor.decompressInts(compressed);

            assertArrayEquals(readings, restored);

            OpenZLCompressionInfo info = compressor.inspect(compressed);
            assertEquals(OpenZLGraph.NUMERIC, info.graph());
            assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, info.flavor());
        }
    }

    @Test
    void sddlPipeline() {
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
        byte[] payload = "12345678".repeat(128).getBytes(StandardCharsets.US_ASCII);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SERIAL, Map.of());
            byte[] serial = compressor.compress(payload);

            compressor.reset();
            compressor.configureSddl(compiled);
            byte[] structured = compressor.compress(payload);

            assertArrayEquals(payload, compressor.decompress(serial));
            assertArrayEquals(payload, compressor.decompress(structured));
            assertTrue(structured.length > 0);
            assertTrue(serial.length > 0);
        }
    }

    @Test
    void inspectCompressionMetadata() {
        byte[] payload = "inspect me for metadata".getBytes(StandardCharsets.UTF_8);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_5);
            byte[] compressed = compressor.compress(payload);

            OpenZLCompressionInfo info = compressor.inspect(compressed);
            assertEquals(payload.length, info.originalSize());
            assertEquals(compressed.length, info.compressedSize());
            assertEquals(OpenZLCompressionInfo.DataFlavor.SERIAL, info.flavor());
            long elements = info.elementCount().orElse(-1L);
            assertTrue(elements >= -1L);
        }
    }
}
