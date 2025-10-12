package io.github.hybledav;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

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
    public native byte[] compress(byte[] input);
    public native byte[] decompress(byte[] input);
    private native int compressDirect(ByteBuffer src, int srcPos, int srcLen,
                                      ByteBuffer dst, int dstPos, int dstLen);
    private native int decompressDirect(ByteBuffer src, int srcPos, int srcLen,
                                        ByteBuffer dst, int dstPos, int dstLen);
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

    private static void requireDirect(ByteBuffer buffer, String name) {
        if (buffer == null) {
            throw new NullPointerException(name + " buffer is null");
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException(name + " buffer must be direct");
        }
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
