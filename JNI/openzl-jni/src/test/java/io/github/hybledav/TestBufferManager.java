package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TestBufferManager {

    @Test
    void managerReturnsDirectBuffers() {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder()
                .minimumCapacity(1024)
                .alignment(128)
                .build()) {
            ByteBuffer buffer = manager.acquire(2048);
            assertTrue(buffer.isDirect());
            assertTrue(buffer.capacity() >= 2048);
            assertEquals(0, buffer.position());
            manager.release(buffer);
        }
    }

    @Test
    void buffersAreReusedWithinThread() {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder().build()) {
            ByteBuffer first = manager.acquire(1024);
            first.put((byte) 1);
            manager.release(first);

            ByteBuffer second = manager.acquire(512);
            assertSame(first, second, "Expected pooled buffer to be reused");
            assertEquals(0, second.position());
            manager.release(second);
        }
    }

    @Test
    void managerWorksWithCompressor() {
        byte[] payload = "buffer-manager-roundtrip".getBytes(StandardCharsets.UTF_8);

        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build();
                OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer src = buffers.acquire(payload.length);
            src.put(payload).flip();

            ByteBuffer compressed = buffers.acquire(payload.length + 1024);
            int compressedBytes = compressor.compress(src, compressed);
            assertTrue(compressedBytes > 0);
            compressed.limit(compressed.position());
            compressed.position(0);
            buffers.release(src);

            byte[] compressedCopy = new byte[compressed.remaining()];
            compressed.duplicate().get(compressedCopy);
            buffers.release(compressed);

            byte[] roundTrip = compressor.decompress(compressedCopy);
            assertArrayEquals(payload, roundTrip);

            ByteBuffer compressedDirect = buffers.acquire(compressedCopy.length);
            compressedDirect.put(compressedCopy).flip();
            long decompressedSize = compressor.getDecompressedSize(compressedCopy);
            ByteBuffer restored = buffers.acquireForDecompression(decompressedSize);
            int restoredBytes = compressor.decompress(compressedDirect, restored);
            assertEquals(roundTrip.length, restoredBytes);
            restored.flip();
            byte[] roundTripDirect = new byte[restoredBytes];
            restored.get(roundTripDirect);
            assertArrayEquals(roundTrip, roundTripDirect);
            buffers.release(compressedDirect);
            buffers.release(restored);
        }
    }

    @Test
    void sizeHelpersAllocateEnoughSpace() {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder()
                .minimumCapacity(256)
                .alignment(64)
                .build()) {
            int inputSize = 128;
            ByteBuffer compressionBuffer = manager.acquireForCompression(inputSize);
            assertTrue(compressionBuffer.capacity() >= OpenZLCompressor.maxCompressedSize(inputSize));
            manager.release(compressionBuffer);

            ByteBuffer decompressionBuffer = manager.acquireForDecompression(4096);
            assertTrue(decompressionBuffer.capacity() >= 4096);
            manager.release(decompressionBuffer);
        }
    }

    @Test
    void managedConvenienceRoundTrip() {
        byte[] payload = "manager-convenience-roundtrip".getBytes(StandardCharsets.UTF_8);
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build();
                OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer src = buffers.acquire(payload.length);
            src.put(payload).flip();

            ByteBuffer compressed = compressor.compress(src, buffers);
            buffers.release(src);
            assertTrue(compressed.isDirect());
            assertTrue(compressed.remaining() > 0);

            ByteBuffer restored = compressor.decompress(compressed, buffers);
            byte[] restoredBytes = new byte[restored.remaining()];
            restored.get(restoredBytes);
            assertArrayEquals(payload, restoredBytes);

            buffers.release(compressed);
            buffers.release(restored);
        }
    }

    @Test
    void alignmentAndMinimumCapacityAreRespected() {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder()
                .minimumCapacity(512)
                .alignment(128)
                .build()) {
            ByteBuffer buffer = manager.acquire(513);
            assertTrue(buffer.capacity() >= 513);
            assertEquals(0, buffer.capacity() % 128, "capacity should be aligned");
            manager.release(buffer);
        }
    }

    @Test
    void buffersGrowWhenDemandDoubles() {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder()
                .minimumCapacity(256)
                .alignment(64)
                .build()) {
            ByteBuffer initial = manager.acquire(512);
            ByteBuffer larger = manager.acquire(initial.capacity() * 4);
            assertNotSame(initial, larger, "manager should allocate a distinct buffer for larger demand");
            assertTrue(larger.capacity() >= initial.capacity() * 4);
            manager.release(initial);
            manager.release(larger);
        }
    }

    @Test
    void buffersRemainThreadLocal() throws InterruptedException, ExecutionException {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder()
                .minimumCapacity(128)
                .alignment(64)
                .build()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> acquireTask = () -> {
                    ByteBuffer buffer = manager.acquire(256);
                    try {
                        return System.identityHashCode(buffer);
                    } finally {
                        manager.release(buffer);
                    }
                };

                Future<Integer> first = executor.submit(acquireTask);
                Future<Integer> second = executor.submit(acquireTask);

                assertNotEquals(first.get().intValue(), second.get().intValue(),
                        "Each thread should receive its own pooled buffer instance");
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate promptly");
            }
        }
    }

    @Test
    void releasingForeignBufferIsNoOp() {
        try (OpenZLBufferManager manager = OpenZLBufferManager.builder().build()) {
            ByteBuffer foreign = ByteBuffer.allocateDirect(32);
            assertDoesNotThrow(() -> manager.release(foreign));

            ByteBuffer buffer = manager.acquire(64);
            manager.release(buffer);
        }
    }
}
