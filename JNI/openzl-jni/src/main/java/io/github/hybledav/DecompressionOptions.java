package io.github.hybledav;

import java.util.Objects;

/** Complete context options. Unspecified fields use upstream defaults; reset() clears these options. */
public final class DecompressionOptions {
    final int[] values;
    private DecompressionOptions(Builder builder) { values = builder.values.clone(); }
    public static Builder builder() { return new Builder(); }
    public ParameterSwitch checkCompressedChecksum() { return ParameterSwitch.values()[values[0]]; }
    public ParameterSwitch checkContentChecksum() { return ParameterSwitch.values()[values[1]]; }
    public ParameterSwitch codecFusion() { return ParameterSwitch.values()[values[2]]; }
    public static final class Builder {
        private final int[] values = new int[3];
        private Builder() {}
        public Builder checkCompressedChecksum(ParameterSwitch value) {
            values[0] = Objects.requireNonNull(value, "checkCompressedChecksum").ordinal();
            return this;
        }
        public Builder checkContentChecksum(ParameterSwitch value) {
            values[1] = Objects.requireNonNull(value, "checkContentChecksum").ordinal();
            return this;
        }
        public Builder codecFusion(ParameterSwitch value) {
            values[2] = Objects.requireNonNull(value, "codecFusion").ordinal();
            return this;
        }
        public DecompressionOptions build() { return new DecompressionOptions(this); }
    }
}
