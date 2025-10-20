package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestBufferManagerGuards {

    @Test
    void acquireForCompressionRejectsNegative() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> buffers.acquireForCompression(-1));
        }
    }

    @Test
    void acquireForDecompressionRejectsNegative() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> buffers.acquireForDecompression(-1));
        }
    }

    @Test
    void releaseIgnoresForeignBuffer() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder()
                .minimumCapacity(32)
                .alignment(8)
                .build()) {
            ByteBuffer foreign = ByteBuffer.allocateDirect(32);
            buffers.release(foreign); // Should be ignored without throwing.

            ByteBuffer managed = buffers.acquire(32);
            buffers.release(managed);

            ByteBuffer again = buffers.acquire(32);
            assertSame(managed, again, "Managed buffer should round-trip through the cache");
            buffers.release(again);
        }
    }

    @Test
    void releaseNullViaPoolStateIsNoOp() throws Exception {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder()
                .minimumCapacity(16)
                .alignment(8)
                .build()) {
            ByteBuffer managed = buffers.acquire(16);
            buffers.release(managed);

            Object poolState = currentPoolState(buffers);
            Method release = poolState.getClass().getDeclaredMethod("release", ByteBuffer.class);
            release.setAccessible(true);
            release.invoke(poolState, new Object[] { null });
        }
    }

    @Test
    void borrowFallsBackToLargerBucketWhenSmallerIsEmpty() throws Exception {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder()
                .minimumCapacity(8)
                .alignment(8)
                .build()) {
            ByteBuffer small = buffers.acquire(72);
            buffers.release(small);

            ByteBuffer large = buffers.acquire(224);
            buffers.release(large);

            Object poolState = currentPoolState(buffers);
            Class<?> poolClass = poolState.getClass();
            Method bucketIndex = poolClass.getDeclaredMethod("bucketIndex", int.class);
            bucketIndex.setAccessible(true);
            Method poll = poolClass.getDeclaredMethod("pollFromBucket", int.class, int.class);
            poll.setAccessible(true);
            Field bucketsField = poolClass.getDeclaredField("buckets");
            bucketsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ArrayDeque<ByteBuffer>> bucketList = (List<ArrayDeque<ByteBuffer>>) bucketsField.get(poolState);

            int smallIndex = (Integer) bucketIndex.invoke(poolState, small.capacity());
            int largeIndex = (Integer) bucketIndex.invoke(poolState, large.capacity());
            ByteBuffer extracted = (ByteBuffer) poll.invoke(poolState, smallIndex, small.capacity() + 40);
            assertSame(large, extracted, "pollFromBucket should skip undersized buffers");
            bucketList.get(largeIndex).addFirst(extracted);

            ByteBuffer reused = buffers.acquire(120);
            assertSame(large, reused, "Should re-use larger bucket buffer when smaller bucket insufficient");
            buffers.release(reused);
        }
    }

    @Test
    void bucketIndexClampsToLargestBucket() throws Exception {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder()
                .minimumCapacity(1 << 20)
                .alignment(1 << 20)
                .build()) {
            Object poolState = currentPoolState(buffers);
            Method bucketIndex = poolState.getClass().getDeclaredMethod("bucketIndex", int.class);
            bucketIndex.setAccessible(true);
            java.lang.reflect.Field bucketsField = poolState.getClass().getDeclaredField("buckets");
            bucketsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ArrayDeque<ByteBuffer>> bucketList = (List<ArrayDeque<ByteBuffer>>) bucketsField.get(poolState);
            long request = 1L << 20; // initial minimum capacity
            for (int i = 1; i < bucketList.size(); i++) {
                request = Math.min((long) Integer.MAX_VALUE, request << 1);
            }
            request = Math.min((long) Integer.MAX_VALUE, request + (1L << 20));
            int index = (Integer) bucketIndex.invoke(poolState, (int) request);
            assertEquals(bucketList.size() - 1, index, "Bucket index should clamp to the last bucket");
        }
    }

    @Test
    void constructorBuildsAllBucketSlots() {
        try (OpenZLBufferManager ignored = OpenZLBufferManager.builder()
                .minimumCapacity(1)
                .alignment(1)
                .build()) {
            // No assertions required; construction should succeed and cover loop branches.
        }
    }

    @Test
    void acquireRejectsCapacityOverflow() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder()
                .minimumCapacity(1)
                .alignment(2)
                .build()) {
            assertThrows(IllegalArgumentException.class, () -> buffers.acquire(Integer.MAX_VALUE));
        }
    }

    @Test
    void checkedCapacityRejectsNegativeAndTooLarge() throws Exception {
        Method checkedCapacity = OpenZLBufferManager.class.getDeclaredMethod("checkedCapacity", long.class);
        checkedCapacity.setAccessible(true);

        assertThrows(IllegalArgumentException.class, () -> invokeInt(checkedCapacity, -5L));
        assertThrows(IllegalArgumentException.class, () -> invokeInt(checkedCapacity, (long) Integer.MAX_VALUE + 1));
        assertEquals(128, invokeInt(checkedCapacity, 128L));
    }

    private static Object currentPoolState(OpenZLBufferManager buffers) throws Exception {
        Field poolField = OpenZLBufferManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> pool = (ThreadLocal<Object>) poolField.get(buffers);
        return pool.get();
    }

    private static int invokeInt(Method method, long argument) throws Exception {
        try {
            return (Integer) method.invoke(null, argument);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw ex;
        }
    }
}
