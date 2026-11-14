package io.github.hybledav;

import java.nio.ByteBuffer;
import java.util.Objects;

final class DictionarySupport {
    private DictionarySupport() {}

    static byte[] bundleId(byte[] frame, int offset, int length) {
        Objects.requireNonNull(frame, "frame");
        Objects.checkFromIndexSize(offset, length, frame.length);
        OpenZLNative.load();
        return bundleIdNative(frame, offset, length);
    }

    static byte[] bundleId(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        if (!frame.isDirect()) throw new IllegalArgumentException("frame must be direct");
        OpenZLNative.load();
        return bundleIdDirectNative(frame, frame.position(), frame.remaining());
    }

    static byte[] resolve(DictionaryBundleProvider provider, byte[] id) {
        byte[] bundle = provider.loadBundle(id.clone());
        if (bundle == null || bundle.length == 0) {
            throw new IllegalStateException("Dictionary bundle is unavailable for the frame's bundle ID");
        }
        return bundle;
    }

    private static native byte[] bundleIdNative(byte[] frame, int offset, int length);
    private static native byte[] bundleIdDirectNative(ByteBuffer frame, int offset, int length);
}
