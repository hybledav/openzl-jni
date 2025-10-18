package io.github.hybledav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

public class TestTraining {

    // Helper to create a set of tiny inputs
    private static byte[][] makeSamples(int n, String base) {
        byte[][] arr = new byte[n][];
        for (int i = 0; i < n; i++) {
            arr[i] = (base + "-" + i).getBytes(StandardCharsets.UTF_8);
        }
        return arr;
    }

    @Test
    public void trainSerialDefaultOptions() {
        byte[][] inputs = makeSamples(3, "alpha");
        byte[][] trained = OpenZLCompressor.train("serial", inputs, null);
        assertNotNull(trained, "trained result should not be null");
        assertTrue(trained.length > 0, "should return at least one trained compressor");
    }

    @Test
    public void trainSerialWithOptions() {
        byte[][] inputs = makeSamples(5, "beta");
        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 1;
        opts.threads = 1;
        opts.numSamples = 0;
        opts.paretoFrontier = false;
        byte[][] trained = OpenZLCompressor.train("serial", inputs, opts);
        assertNotNull(trained);
        assertTrue(trained.length >= 1);
    }

    @Test
    public void trainCsvProfileSmall() {
        byte[][] inputs = makeSamples(4, "csvline");
        byte[][] trained = OpenZLCompressor.train("csv", inputs, new TrainOptions());
        assertNotNull(trained);
    }

    @Test
    public void trainSddlProfileNoPareto() {
        byte[][] inputs = makeSamples(2, "sddl");
        TrainOptions opts = new TrainOptions();
        opts.paretoFrontier = false;
        assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.train("sddl", inputs, opts));
    }

    @Test
    public void trainSddlProfileParetoTrue() {
        byte[][] inputs = makeSamples(2, "sddl2");
        TrainOptions opts = new TrainOptions();
        opts.paretoFrontier = true;
        assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.train("sddl", inputs, opts));
    }

    @Test
    public void trainWithZeroLengthInputs() {
        byte[][] inputs = new byte[3][];
        inputs[0] = new byte[0];
        inputs[1] = "a".getBytes(StandardCharsets.UTF_8);
        inputs[2] = new byte[0];
        byte[][] trained = OpenZLCompressor.train("serial", inputs, new TrainOptions());
        assertNotNull(trained);
    }

    @Test
    public void trainUnknownProfileShouldThrow() {
        byte[][] inputs = makeSamples(2, "x");
        assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.train("no-such-profile", inputs, null));
    }

    @Test
    public void trainFromDirectoryDirectCall() throws Exception {
        // Use same helper as OpenZLCompressor.train but call trainFromDirectory directly
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("openzl-train-test");
        try {
            for (int i = 0; i < 3; i++) {
                java.nio.file.Files.write(tmp.resolve(Integer.toString(i)), ("v" + i).getBytes(StandardCharsets.UTF_8));
            }
            TrainOptions opts = new TrainOptions();
            byte[][] trained = OpenZLCompressor.trainFromDirectory("serial", tmp.toString(), opts);
            assertNotNull(trained);
            assertTrue(trained.length >= 1);
        } finally {
            for (java.nio.file.Path p : java.nio.file.Files.newDirectoryStream(tmp)) {
                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void trainManySmallSamples() {
        byte[][] inputs = makeSamples(10, "many");
        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 1;
        byte[][] trained = OpenZLCompressor.train("serial", inputs, opts);
        assertNotNull(trained);
    }

    @Test
    public void trainParallelThreads() {
        byte[][] inputs = makeSamples(6, "par");
        TrainOptions opts = new TrainOptions();
        opts.threads = 2;
        byte[][] trained = OpenZLCompressor.train("serial", inputs, opts);
        assertNotNull(trained);
    }

    @Test
    public void trainCsvParetoAndThreads() {
        byte[][] inputs = makeSamples(6, "csvpar");
        TrainOptions opts = new TrainOptions();
        opts.threads = 1;
        opts.paretoFrontier = true;
        byte[][] trained = OpenZLCompressor.train("csv", inputs, opts);
        assertNotNull(trained);
    }

    @Test
    public void trainedShouldBeatUntrainedSerial() {
        byte[][] inputs = makeSamples(8, "improve");
        // Train using serial profile
        byte[][] trained = OpenZLCompressor.train("serial", inputs, new TrainOptions());
        assertNotNull(trained);
        assertTrue(trained.length > 0);
        byte[] serializedCompressor = trained[0];

        // Pick a sample input to compare
        byte[] sample = inputs[0];

        // Compress with untrained (profile) compressor
        byte[] untrainedCompressed = OpenZLCompressor.compressWithProfileNative("serial", sample);
        assertNotNull(untrainedCompressed);

        // Compress with trained compressor
        byte[] trainedCompressed = OpenZLCompressor.compressWithSerializedNative("serial", serializedCompressor, sample);
        assertNotNull(trainedCompressed);

        assertTrue(trainedCompressed.length <= untrainedCompressed.length,
                () -> String.format("trained %d <= untrained %d", trainedCompressed.length, untrainedCompressed.length));
    }

    @Test
    public void trainedShouldBeatUntrainedCsv() {
        byte[][] inputs = new byte[12][];
        for (int i = 0; i < 12; ++i) {
            String s = "col1,col2,col3\n" + "row" + i + ",val" + i + ",x\n";
            inputs[i] = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 1;
        byte[][] trained = OpenZLCompressor.train("csv", inputs, opts);
        assertNotNull(trained);
        assertTrue(trained.length > 0);
        byte[] serializedCompressor = trained[0];

        byte[] sample = inputs[1];
        byte[] untrainedCompressed = OpenZLCompressor.compressWithProfileNative("csv", sample);
        assertNotNull(untrainedCompressed);
        byte[] trainedCompressed = OpenZLCompressor.compressWithSerializedNative("csv", serializedCompressor, sample);
        assertNotNull(trainedCompressed);

        assertTrue(trainedCompressed.length <= untrainedCompressed.length,
                () -> String.format("trained %d <= untrained %d", trainedCompressed.length, untrainedCompressed.length));
    }
}
