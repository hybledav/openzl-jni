package io.github.hybledav;

import java.util.Objects;

/** Complete compression-context options. Unspecified fields use upstream defaults (or
 * inherited compressor settings); reset() clears these context overrides. Context options
 * take precedence over the legacy setParameter/getParameter compressor settings.
 * Supported format versions are 8..27; 0 selects the inherited/default version.
 */
public final class CompressionOptions {
    final int[] values;
    private CompressionOptions(Builder builder) { values = builder.values.clone(); }
    public static Builder builder() { return new Builder(); }
    public int compressionLevel() { return values[0]; }
    public int decompressionLevel() { return values[1]; }
    public int formatVersion() { return values[2]; }
    public ParameterSwitch permissive() { return ParameterSwitch.values()[values[3]]; }
    public ParameterSwitch compressedChecksum() { return ParameterSwitch.values()[values[4]]; }
    public ParameterSwitch contentChecksum() { return ParameterSwitch.values()[values[5]]; }
    public int minStreamSize() { return values[6]; }
    public ParameterSwitch storeOnExpansion() { return ParameterSwitch.values()[values[7]]; }
    public static final class Builder {
        private final int[] values = new int[8];
        private Builder() {}
        public Builder compressionLevel(int value) {
            if (value < -10000 || value > 7) throw new IllegalArgumentException("compressionLevel out of range");
            values[0] = value;
            return this;
        }
        public Builder decompressionLevel(int value) {
            if (value < 0 || value > 7) throw new IllegalArgumentException("decompressionLevel out of range");
            values[1] = value;
            return this;
        }
        public Builder formatVersion(int value) {
            if (value < 0 || value > 27) throw new IllegalArgumentException("formatVersion out of range");
            if (value != 0 && value < 8) throw new IllegalArgumentException("formatVersion must be 0 or 8..27");
            values[2] = value;
            return this;
        }
        public Builder permissive(ParameterSwitch value) {
            values[3] = Objects.requireNonNull(value, "permissive").ordinal();
            return this;
        }
        public Builder compressedChecksum(ParameterSwitch value) {
            values[4] = Objects.requireNonNull(value, "compressedChecksum").ordinal();
            return this;
        }
        public Builder contentChecksum(ParameterSwitch value) {
            values[5] = Objects.requireNonNull(value, "contentChecksum").ordinal();
            return this;
        }
        public Builder minStreamSize(int value) {
            if (value < -1) throw new IllegalArgumentException("minStreamSize out of range");
            values[6] = value;
            return this;
        }
        public Builder storeOnExpansion(ParameterSwitch value) {
            values[7] = Objects.requireNonNull(value, "storeOnExpansion").ordinal();
            return this;
        }
        public CompressionOptions build() { return new CompressionOptions(this); }
    }
}
