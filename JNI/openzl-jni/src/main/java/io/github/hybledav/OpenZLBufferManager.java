package io.github.hybledav;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Small helper that hands out reusable direct {@link ByteBuffer}s.
 * <p>
 * Compress/decompress calls are most efficient when users provide direct
 * buffers. This manager keeps a per-thread cache so callers can obtain
 * a buffer of at least the desired capacity without constantly allocating.
 */
public final class OpenZLBufferManager implements AutoCloseable {
    private final ThreadLocal<PoolState> pool;
    private final int alignment;
    private final int minimumCapacity;

    private OpenZLBufferManager(int minimumCapacity, int alignment) {
        this.minimumCapacity = Math.max(1, minimumCapacity);
        this.alignment = Math.max(1, alignment);
        this.pool = ThreadLocal.withInitial(PoolState::new);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Acquire a direct {@link ByteBuffer} with the given minimum capacity.
     * The buffer is cleared before returning.
     */
    public ByteBuffer acquire(int minCapacity) {
        return pool.get().borrow(minCapacity);
    }

    /**
     * Acquire a buffer large enough for compressing an input of {@code inputSize} bytes.
     */
    public ByteBuffer acquireForCompression(int inputSize) {
        if (inputSize < 0) {
            throw new IllegalArgumentException("inputSize must be non-negative");
        }
        long required = OpenZLCompressor.maxCompressedSize(inputSize);
        return acquire(checkedCapacity(required));
    }

    /**
     * Acquire a buffer large enough to hold a payload with {@code decompressedSize} bytes.
     */
    public ByteBuffer acquireForDecompression(long decompressedSize) {
        if (decompressedSize < 0) {
            throw new IllegalArgumentException("decompressedSize must be non-negative");
        }
        return acquire(checkedCapacity(decompressedSize));
    }

    /**
     * Release the buffer so it can be reused by subsequent {@link #acquire(int)} calls.
     * Buffers not originally allocated by this manager are ignored.
     */
    public void release(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        pool.get().release(buffer);
    }

    @Override
    public void close() {
        PoolState state = pool.get();
        state.clear();
        pool.remove();
    }

    private int roundedCapacity(int requested) {
        long rounded = ((long) requested + alignment - 1) / alignment * alignment;
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested capacity too large: " + requested);
        }
        return (int) rounded;
    }

    private static int checkedCapacity(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("Required capacity cannot be negative: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required capacity exceeds ByteBuffer limit: " + size);
        }
        return (int) size;
    }

    private static ByteBuffer allocateDirect(int capacity) {
        return ByteBuffer.allocateDirect(capacity);
    }

    private final class PoolState {
        private final ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
        private final ArrayDeque<ByteBuffer> inUse = new ArrayDeque<>();

        ByteBuffer borrow(int minCapacity) {
            int required = Math.max(minCapacity, minimumCapacity);
            int capacity = roundedCapacity(required);
            for (java.util.Iterator<ByteBuffer> it = free.iterator(); it.hasNext();) {
                ByteBuffer candidate = it.next();
                if (candidate.capacity() >= capacity) {
                    it.remove();
                    candidate.clear();
                    inUse.addLast(candidate);
                    return candidate;
                }
            }

            ByteBuffer buffer = allocateDirect(capacity);
            inUse.addLast(buffer);
            buffer.clear();
            return buffer;
        }

        void release(ByteBuffer buffer) {
            if (buffer == null) {
                return;
            }
            if (inUse.removeLastOccurrence(buffer)) {
                buffer.clear();
                free.addLast(buffer);
            }
        }

        void clear() {
            free.clear();
            inUse.clear();
        }
    }

    public static final class Builder {
        private int minimumCapacity = 64 * 1024;
        private int alignment = 64;

        private Builder() {}

        public Builder minimumCapacity(int value) {
            this.minimumCapacity = value;
            return this;
        }

        public Builder alignment(int value) {
            this.alignment = value;
            return this;
        }

        public OpenZLBufferManager build() {
            return new OpenZLBufferManager(minimumCapacity, alignment);
        }
    }
}
