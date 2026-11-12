package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

class TestDirectBufferWriteGuards {
    @Test
    void overlappingArrayRangesAreRejectedBeforeWriting() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] storage = new byte[256];
            java.util.Arrays.fill(storage, (byte) 7);
            byte[] original = storage.clone();
            assertThrows(IllegalArgumentException.class,
                    () -> compressor.compress(storage, 0, 64, storage, 32, 200));
            assertArrayEquals(original, storage);
            byte[] frame = compressor.compress(new byte[64]);
            System.arraycopy(frame, 0, storage, 0, frame.length);
            byte[] encoded = storage.clone();
            assertThrows(IllegalArgumentException.class,
                    () -> compressor.decompress(storage, 0, frame.length, storage, 8, 64));
            assertArrayEquals(encoded, storage);
        }
    }

    @Test
    void overlappingDirectViewsAreRejectedBeforeWriting() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer storage = ByteBuffer.allocateDirect(256);
            ByteBuffer input = storage.duplicate().limit(64);
            ByteBuffer output = storage.duplicate().position(32);
            assertThrows(IllegalArgumentException.class, () -> compressor.compress(input, output));
            assertEquals(0, input.position());
            assertEquals(32, output.position());
            byte[] frame = compressor.compress(new byte[64]);
            storage.put(frame);
            ByteBuffer encoded = storage.duplicate().position(0).limit(frame.length);
            ByteBuffer destination = storage.duplicate().position(8).limit(72);
            assertThrows(IllegalArgumentException.class, () -> compressor.decompress(encoded, destination));
            byte[] unchanged = new byte[frame.length];
            storage.position(0).get(unchanged);
            assertArrayEquals(frame, unchanged);
        }
    }

    @Test
    void disjointRegionsOfTheSameAllocationRemainUsable() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] bytes = new byte[1024];
            for (int i = 0; i < 64; i++) bytes[i] = (byte) i;
            int size = compressor.compress(bytes, 0, 64, bytes, 256, 512);
            assertEquals(64, compressor.decompress(bytes, 256, size, bytes, 0, 64));
            for (int i = 0; i < 64; i++) assertEquals((byte) i, bytes[i]);
            ByteBuffer storage = ByteBuffer.allocateDirect(1024);
            for (int i = 0; i < 64; i++) storage.put(i, (byte) i);
            ByteBuffer input = storage.duplicate().limit(64);
            ByteBuffer output = storage.duplicate().position(256).limit(768);
            int written = compressor.compress(input, output);
            ByteBuffer encoded = storage.duplicate().position(256).limit(256 + written);
            ByteBuffer decoded = storage.duplicate().limit(64);
            assertEquals(64, compressor.decompress(encoded, decoded));
            for (int i = 0; i < 64; i++) assertEquals((byte) i, storage.get(i));
            assertDoesNotThrow(() -> compressor.compress(bytes, 64, 0, bytes, 0, 128));
            ByteBuffer empty = storage.duplicate().position(64).limit(64);
            ByteBuffer emptyOutput = storage.duplicate().limit(128);
            assertDoesNotThrow(() -> compressor.compress(empty, emptyOutput));
        }
    }

    @Test
    void protobufConversionRejectsReadOnlyDestination() {
        ByteBuffer input = ByteBuffer.allocateDirect(1);
        ByteBuffer output = ByteBuffer.allocateDirect(128).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> OpenZLProtobuf.convertInto(input, 0,
                OpenZLProtobuf.Protocol.PROTO, OpenZLProtobuf.Protocol.PROTO, output, SchemaFixtures.descriptor()));
    }

    @Test
    void compressionRejectsReadOnlyDestinationWithoutWritingOrConsumingInput() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer input = ByteBuffer.allocateDirect(32).putLong(123).flip();
            ByteBuffer storage = ByteBuffer.allocateDirect(256);
            ByteBuffer destination = storage.asReadOnlyBuffer();
            assertThrows(ReadOnlyBufferException.class, () -> compressor.compress(input, destination));
            assertEquals(0, input.position());
            assertEquals(0, destination.position());
            for (int i = 0; i < storage.capacity(); i++) assertEquals(0, storage.get(i));
        }
    }

    @Test
    void decompressionRejectsReadOnlyDestinationWithoutWritingOrConsumingInput() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] frame = compressor.compress(new byte[] {1, 2, 3, 4});
            ByteBuffer input = ByteBuffer.allocateDirect(frame.length).put(frame).flip();
            ByteBuffer storage = ByteBuffer.allocateDirect(32);
            ByteBuffer destination = storage.asReadOnlyBuffer();
            assertThrows(ReadOnlyBufferException.class, () -> compressor.decompress(input, destination));
            assertEquals(0, input.position());
            assertEquals(0, destination.position());
            for (int i = 0; i < storage.capacity(); i++) assertEquals(0, storage.get(i));
        }
    }
}
