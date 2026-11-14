package io.github.hybledav;

/**
 * Resolves the serialized fat dictionary bundle referenced by a frame.
 * Callbacks run synchronously before decoding, outside native array pinning.
 * A provider shared by multiple compressors must support concurrent calls.
 */
@FunctionalInterface
public interface DictionaryBundleProvider {
    /**
     * @param bundleId a defensive copy of the frame's 32-byte bundle ID
     * @return the complete serialized bundle, or null if unavailable
     * @throws RuntimeException if retrieval fails; propagated to the caller
     */
    byte[] loadBundle(byte[] bundleId);
}
