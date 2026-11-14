package io.github.hybledav;

import java.util.Objects;

/** Local LZ graph controls. Unspecified settings use upstream defaults. Entropy thresholds use -1 for default; 0 disables the minimum.
 * Ranges are deliberately checked instead of silently clamping as the native API does.
 */
public final class LzOptions {
    public enum Strategy { DEFAULT, FAST, DOUBLE_FAST, GREEDY, LAZY, LAZY2 }
    final int[] values;
    private LzOptions(Builder builder) { values = builder.values.clone(); }
    public static Builder builder() { return new Builder(); }
    public int compressionLevel() { return values[0]; }
    public int acceleration() { return values[1]; }
    public int windowLog() { return values[2]; }
    public int hashLog1() { return values[3]; }
    public int hashLog2() { return values[4]; }
    public int hashLength() { return values[5]; }
    public int searchLog() { return values[6]; }
    public int entropyMinGainBytes() { return values[7]; }
    public int entropyMinGainPercent() { return values[8]; }
    public Strategy strategy() { return Strategy.values()[values[9]]; }
    public static final class Builder {
        private final int[] values = new int[10];
        private Builder() { values[7] = -1; values[8] = -1; }
        public Builder compressionLevel(int value) {
            if (value != 0 && (value < -10000 || value > 7)) throw new IllegalArgumentException("compressionLevel out of range");
            values[0] = value;
            return this;
        }
        public Builder acceleration(int value) {
            if (value != 0 && (value < 1 || value > 10000)) throw new IllegalArgumentException("acceleration out of range");
            values[1] = value;
            return this;
        }
        public Builder windowLog(int value) {
            if (value != 0 && (value < 10 || value > 28)) throw new IllegalArgumentException("windowLog out of range");
            values[2] = value;
            return this;
        }
        public Builder hashLog1(int value) {
            if (value != 0 && (value < 10 || value > 23)) throw new IllegalArgumentException("hashLog1 out of range");
            values[3] = value;
            return this;
        }
        public Builder hashLog2(int value) {
            if (value != 0 && (value < 10 || value > 23)) throw new IllegalArgumentException("hashLog2 out of range");
            values[4] = value;
            return this;
        }
        public Builder hashLength(int value) {
            if (value != 0 && (value < 4 || value > 7)) throw new IllegalArgumentException("hashLength out of range");
            values[5] = value;
            return this;
        }
        public Builder searchLog(int value) {
            if (value != 0 && (value < 1 || value > 4)) throw new IllegalArgumentException("searchLog out of range");
            values[6] = value;
            return this;
        }
        public Builder entropyMinGainBytes(int value) {
            if (value < -1) throw new IllegalArgumentException("entropyMinGainBytes out of range");
            values[7] = value;
            return this;
        }
        public Builder entropyMinGainPercent(int value) {
            if ((value < -1 || value > 100)) throw new IllegalArgumentException("entropyMinGainPercent out of range");
            values[8] = value;
            return this;
        }
        public Builder strategy(Strategy value) {
            values[9] = Objects.requireNonNull(value, "strategy").ordinal();
            return this;
        }
        public LzOptions build() { return new LzOptions(this); }
    }
}
