package io.github.hybledav;

import java.util.Objects;

/**
 * A reusable trained graph and its optional dictionary bundle. Save both byte
 * arrays when persisting a candidate; the graph alone may depend on the bundle.
 * Instances own defensive copies and can outlive the training call.
 */
public final class TrainedCompressor {
    private final String profileName;
    private final byte[] serializedCompressor;
    private final byte[] dictionaryBundle;

    public TrainedCompressor(String profileName, byte[] serializedCompressor, byte[] dictionaryBundle) {
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(serializedCompressor, "serializedCompressor");
        Objects.requireNonNull(dictionaryBundle, "dictionaryBundle");
        if (profileName.isBlank() || serializedCompressor.length == 0) {
            throw new IllegalArgumentException("A profile name and serialized compressor are required");
        }
        this.serializedCompressor = serializedCompressor.clone();
        this.dictionaryBundle = dictionaryBundle.clone();
    }

    public String profileName() { return profileName; }
    public byte[] serializedCompressor() { return serializedCompressor.clone(); }
    public byte[] dictionaryBundle() { return dictionaryBundle.clone(); }
    public boolean hasDictionaryBundle() { return dictionaryBundle.length != 0; }
}
