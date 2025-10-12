package io.github.hybledav;

import java.lang.ref.Cleaner;

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
    private native void destroyCompressor();

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
