package io.github.hybledav;

public final class TrainOptions {
    // optional, 0 means unspecified
    public int maxTimeSecs = 1; // small default for tests
    public int threads = 1;
    public int numSamples = 0; // 0 == unspecified
    public boolean paretoFrontier = false;

    public TrainOptions() {}
}
