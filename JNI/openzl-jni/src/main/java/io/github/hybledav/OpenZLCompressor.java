package io.github.hybledav;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Objects;

public class OpenZLCompressor implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private long nativeHandle;
    private final Cleaner.Cleanable cleanable;

    public OpenZLCompressor() {
        OpenZLNative.load();
        nativeHandle = createCompressor();
        cleanable = CLEANER.register(this, new Releaser(this));
    }

    private native long createCompressor();
    public native void setParameter(int param, int value);
    public native int getParameter(int param);
    public native String serialize();
    public native String serializeToJson();
    public static long maxCompressedSize(int inputSize) {
        if (inputSize < 0) {
            throw new IllegalArgumentException("inputSize must be non-negative");
        }
        OpenZLNative.load();
        long bound = maxCompressedSizeNative(inputSize);
        if (bound < 0) {
            throw new IllegalStateException("Failed to query compression bound");
        }
        return bound;
    }
    public native byte[] compress(byte[] input);
    public native byte[] decompress(byte[] input);
    private static native long maxCompressedSizeNative(int inputSize);
    private native int compressDirect(ByteBuffer src, int srcPos, int srcLen,
                                      ByteBuffer dst, int dstPos, int dstLen);
    private native int decompressDirect(ByteBuffer src, int srcPos, int srcLen,
                                        ByteBuffer dst, int dstPos, int dstLen);
    private native long getDecompressedSizeNative(byte[] input);
    private native long getDecompressedSizeDirect(ByteBuffer src, int srcPos, int srcLen);
    private native void resetNative();
    private native void destroyCompressor();

    public int compress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        int srcPos = src.position();
        int dstPos = dst.position();
        int written = compressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Compression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    public ByteBuffer compress(ByteBuffer src, OpenZLBufferManager buffers) {
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        long bound = maxCompressedSize(src.remaining());
        ByteBuffer dst = buffers.acquire(checkedCapacity(bound));
        int written = compress(src, dst);
        dst.limit(written);
        dst.position(0);
        return dst;
    }

    public int decompress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        int srcPos = src.position();
        int dstPos = dst.position();
        int written = decompressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    public ByteBuffer decompress(ByteBuffer src, OpenZLBufferManager buffers) {
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        ByteBuffer srcView = src.duplicate();
        long expected = getDecompressedSize(srcView);
        ByteBuffer dst = buffers.acquire(checkedCapacity(expected));
        int written = decompress(srcView, dst);
        src.position(src.limit());
        dst.limit(written);
        dst.position(0);
        return dst;
    }

    private static void requireDirect(ByteBuffer buffer, String name) {
        if (buffer == null) {
            throw new NullPointerException(name + " buffer is null");
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException(name + " buffer must be direct");
        }
    }

    public long getDecompressedSize(byte[] input) {
        Objects.requireNonNull(input, "input");
        long result = getDecompressedSizeNative(input);
        if (result < 0) {
            throw new IllegalStateException("Failed to query decompressed size");
        }
        return result;
    }

    public long getDecompressedSize(ByteBuffer input) {
        requireDirect(input, "input");
        long result = getDecompressedSizeDirect(input, input.position(), input.remaining());
        if (result < 0) {
            throw new IllegalStateException("Failed to query decompressed size");
        }
        return result;
    }

    public void reset() {
        resetNative();
    }

    private static int checkedCapacity(long size) {
        if (size < 0) {
            throw new IllegalStateException("Native reported negative capacity: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required capacity exceeds ByteBuffer limit: " + size);
        }
        return (int) size;
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private synchronized void cleanup() {
        if (nativeHandle != 0) {
            destroyCompressor();
            nativeHandle = 0;
        }
    }

    private static final class Releaser implements Runnable {
        private final OpenZLCompressor owner;

        private Releaser(OpenZLCompressor owner) {
            this.owner = owner;
        }

        @Override
        public void run() {
            owner.cleanup();
        }
    }
}
