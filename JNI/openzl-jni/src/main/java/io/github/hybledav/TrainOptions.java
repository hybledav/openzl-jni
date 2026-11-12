package io.github.hybledav;

public final class TrainOptions {
    // optional, 0 means unspecified
    public int maxTimeSecs = 1; // small default for tests
    public int threads = 1;
    public int numSamples = 0; // 0 == unspecified
    public boolean paretoFrontier = false;
    /** Maximum returned candidates, in upstream preference order; zero leaves the upstream default. */
    public int maxNumCandidates = 0;
    /** Target frame format; zero selects the native default (27 in OpenZL 0.3.0). */
    public int formatVersion = 0;
    /** Train Zstandard dictionaries; requires the trainCandidates API. */
    public boolean dictionaryTraining = false;

    void validate() {
        if (maxTimeSecs < 0 || threads < 0 || numSamples < 0 || maxNumCandidates < 0 || formatVersion < 0) {
            throw new IllegalArgumentException("Training limits and formatVersion must not be negative");
        }
    }

    void requireSelfContained() {
        validate();
        if (dictionaryTraining) {
            throw new IllegalArgumentException("Dictionary bundles cannot be returned by byte[][] training methods");
        }
    }

    public TrainOptions() {}
}
