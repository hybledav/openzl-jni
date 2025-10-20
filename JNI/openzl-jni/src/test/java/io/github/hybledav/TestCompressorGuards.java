package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestCompressorGuards {

    @Test
    void maxCompressedSizeRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.maxCompressedSize(-1));
    }

    @Test
    void compressDirectRejectsNullSource() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer dst = ByteBuffer.allocateDirect(16);
            assertThrows(NullPointerException.class, () -> compressor.compress((ByteBuffer) null, dst));
        }
    }

    @Test
    void compressDirectRejectsNonDirectBuffers() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer heapSrc = ByteBuffer.allocate(8);
            ByteBuffer directSrc = ByteBuffer.allocateDirect(8);
            ByteBuffer heapDst = ByteBuffer.allocate(128);
            ByteBuffer directDst = ByteBuffer.allocateDirect(128);

            heapSrc.putInt(1).flip();
            directSrc.putInt(2).flip();

            assertThrows(IllegalArgumentException.class, () -> compressor.compress(heapSrc, directDst));
            assertThrows(IllegalArgumentException.class, () -> compressor.compress(directSrc, heapDst));
        }
    }

    @Test
    void compressDirectFailsWhenDestinationTooSmall() {
        byte[] payload = "insufficient-destination".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer src = ByteBuffer.allocateDirect(payload.length);
            src.put(payload).flip();
            ByteBuffer dst = ByteBuffer.allocateDirect(1);
            assertThrows(IllegalStateException.class, () -> compressor.compress(src, dst));
        }
    }

    @Test
    void decompressDirectRejectsNonDirectBuffers() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] payload = "direct-reject".getBytes(StandardCharsets.UTF_8);
            byte[] compressed = compressor.compress(payload);

            ByteBuffer compressedBuffer = ByteBuffer.allocateDirect(compressed.length);
            compressedBuffer.put(compressed).flip();
            ByteBuffer heapDst = ByteBuffer.allocate(payload.length);
            assertThrows(IllegalArgumentException.class, () -> compressor.decompress(compressedBuffer, heapDst));
        }
    }

    @Test
    void decompressDirectFailsWhenDestinationTooSmall() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] payload = "0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
            byte[] compressed = compressor.compress(payload);

            ByteBuffer compressedBuffer = ByteBuffer.allocateDirect(compressed.length);
            compressedBuffer.put(compressed).flip();
            ByteBuffer tooSmall = ByteBuffer.allocateDirect(payload.length / 2);
            assertThrows(IllegalStateException.class, () -> compressor.decompress(compressedBuffer, tooSmall));
        }
    }

    @Test
    void compressWithBufferManagerRejectsNullManager() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer src = ByteBuffer.allocateDirect(8);
            src.putInt(1).flip();
            assertThrows(NullPointerException.class, () -> compressor.compress(src, (OpenZLBufferManager) null));
        }
    }

    @Test
    void decompressWithBufferManagerRejectsNullManager() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] payload = "buffer-manager-null".getBytes(StandardCharsets.UTF_8);
            byte[] compressed = compressor.compress(payload);
            ByteBuffer src = ByteBuffer.allocateDirect(compressed.length);
            src.put(compressed).flip();
            assertThrows(NullPointerException.class, () -> compressor.decompress(src, (OpenZLBufferManager) null));
        }
    }

    @Test
    void compressedByteArrayRejectsNullOutput() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.compress(new byte[4], null));
        }
    }

    @Test
    void compressByteArrayValidatesRanges() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] input = new byte[8];
            byte[] output = new byte[16];

            assertThrows(IndexOutOfBoundsException.class,
                    () -> compressor.compress(input, 0, input.length + 1, output, 0, output.length));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> compressor.compress(input, 0, input.length, output, 10, output.length));
        }
    }

    @Test
    void decompressByteArrayValidatesRanges() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] payload = "range-check".getBytes(StandardCharsets.UTF_8);
            byte[] compressed = compressor.compress(payload);
            byte[] output = new byte[payload.length];

            assertThrows(IndexOutOfBoundsException.class,
                    () -> compressor.decompress(compressed, 0, compressed.length, output, payload.length, 1));
        }
    }

    @Test
    void typedCompressRejectsNullAndHandlesEmpty() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.compressInts(null));
            byte[] empty = compressor.compressInts(new int[0]);
            assertEquals(0, empty.length);
        }
    }

    @Test
    void typedDecompressRejectsEmptyPayload() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(IllegalArgumentException.class, () -> compressor.decompressInts(new byte[0]));
        }
    }

    @Test
    void configureProfileRejectsNullProfileAndNullEntries() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.configureProfile(null));

            Map<String, String> withNullValue = new HashMap<>();
            withNullValue.put("path", null);
            assertThrows(NullPointerException.class,
                    () -> compressor.configureProfile(OpenZLProfile.SERIAL, withNullValue));

            Map<String, String> withNullKey = new HashMap<>();
            withNullKey.put(null, "value");
            assertThrows(NullPointerException.class,
                    () -> compressor.configureProfile(OpenZLProfile.SERIAL, withNullKey));

            assertDoesNotThrow(() -> compressor.configureProfile(OpenZLProfile.SERIAL, null));
        }
    }

    @Test
    void setCompressionLevelRejectsNull() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.setCompressionLevel(null));
        }
    }

    @Test
    void methodsFailAfterClose() {
        OpenZLCompressor compressor = new OpenZLCompressor();
        compressor.close();
        assertThrows(IllegalStateException.class, () -> compressor.compressInts(new int[] { 1 }));
    }

    @Test
    void getDecompressedSizeByteArrayRejectsNull() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.getDecompressedSize((byte[]) null));
        }
    }

    @Test
    void getDecompressedSizeByteBufferRequiresDirectBuffer() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer heap = ByteBuffer.allocate(8);
            assertThrows(IllegalArgumentException.class, () -> compressor.getDecompressedSize(heap));
        }
    }

    @Test
    void checkedCapacityGuardsAgainstInvalidValues() throws Exception {
        Method method = OpenZLCompressor.class.getDeclaredMethod("checkedCapacity", long.class);
        method.setAccessible(true);

        assertThrows(IllegalStateException.class, () -> invokeCheckedCapacity(method, -1));
        assertThrows(IllegalArgumentException.class, () -> invokeCheckedCapacity(method, (long) Integer.MAX_VALUE + 1));
        assertEquals(256, invokeCheckedCapacity(method, 256));
    }

    @Test
    void requireDirectGuardBranchesCovered() throws Exception {
        Method method = OpenZLCompressor.class.getDeclaredMethod("requireDirect", ByteBuffer.class, String.class);
        method.setAccessible(true);
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> invokeRequireDirect(method, null, "src"));
        assertTrue(npe.getMessage().contains("src"));

        ByteBuffer heap = ByteBuffer.allocate(8);
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> invokeRequireDirect(method, heap, "src"));
        assertTrue(iae.getMessage().contains("src buffer must be direct"));

        assertDoesNotThrow(() -> invokeRequireDirect(method, ByteBuffer.allocateDirect(8), "src"));
    }

    @Test
    void toCompressionInfoRejectsInvalidMetadata() throws Exception {
        Method method = OpenZLCompressor.class.getDeclaredMethod("toCompressionInfo", long[].class);
        method.setAccessible(true);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(IllegalStateException.class,
                    () -> invokeToCompressionInfo(method, compressor, null));
        }
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(IllegalStateException.class,
                    () -> invokeToCompressionInfo(method, compressor, new long[] { 1, 2, 3 }));
        }
    }

    private static int invokeCheckedCapacity(Method method, long arg) throws Exception {
        try {
            return (Integer) method.invoke(null, arg);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw ex;
        }
    }

    private static void invokeRequireDirect(Method method, ByteBuffer buffer, String name) throws Exception {
        try {
            method.invoke(null, buffer, name);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw ex;
        }
    }

    private static OpenZLCompressionInfo invokeToCompressionInfo(Method method, OpenZLCompressor compressor, long[] meta) throws Exception {
        try {
            return (OpenZLCompressionInfo) method.invoke(compressor, (Object) meta);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw ex;
        }
    }
}
