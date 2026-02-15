package io.github.hybledav.bench.protobuf;

import io.github.hybledav.OpenZLProtobuf;
import io.github.hybledav.TrainOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class OpenZLTrainer {
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURE_ITERATIONS = 10;
    private static final int EVALUATION_SAMPLE_COUNT = 8;
    private static final String SELECTOR_MODE =
            System.getProperty("bench.openzl.selector", "diverse").trim().toLowerCase();
    private static final double MIN_COMPETITIVE_RATIO =
            Double.parseDouble(System.getProperty("bench.openzl.min.ratio", "1.0"));
    private static final long MAX_ENCODE_MICROS =
            Long.getLong("bench.openzl.max.encode.us", -1L);
    private static final long MAX_DECODE_MICROS =
            Long.getLong("bench.openzl.max.decode.us", -1L);
    private static final long MAX_TOTAL_MICROS =
            Long.getLong("bench.openzl.max.total.us", -1L);
    private static final long EXPECTED_TOTAL_MICROS =
            Long.getLong("bench.openzl.expected.total.us", -1L);
    private static final long EXPECTED_TOLERANCE_MICROS =
            Long.getLong("bench.openzl.expected.tolerance.us", -1L);

    private OpenZLTrainer() {}

    static List<OpenZLTrainedCodec> trainCandidateCodecs(String messageType,
                                                         byte[][] trainingSamples,
                                                         TrainOptions options,
                                                         int maxCandidates) {
        byte[][] trained = OpenZLProtobuf.train(trainingSamples, OpenZLProtobuf.Protocol.PROTO, options, messageType);
        if (trained == null || trained.length == 0) {
            throw new IllegalStateException("OpenZL training returned no compressors");
        }

        List<OpenZLCandidate> selected = selectByPerformance(trained, trainingSamples, messageType, maxCandidates);
        List<OpenZLTrainedCodec> codecs = new ArrayList<>(selected.size());
        for (OpenZLCandidate candidate : selected) {
            codecs.add(new OpenZLTrainedCodec(candidate.name, messageType, candidate.bytes));
        }
        return codecs;
    }

    static String summarizeSelected(List<OpenZLTrainedCodec> codecs) {
        StringBuilder out = new StringBuilder();
        for (OpenZLTrainedCodec codec : codecs) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(codec.name()).append('=').append(codec.compressorBytes()).append('B');
        }
        return out.toString();
    }

    /**
     * Returns a detailed summary including performance characteristics discovered
     * during candidate selection.
     */
    static String summarizeWithMetrics(List<OpenZLTrainedCodec> codecs,
                                       byte[][] samples,
                                       String messageType) {
        StringBuilder out = new StringBuilder();
        byte[] sample = selectRepresentativeSample(samples);

        for (OpenZLTrainedCodec codec : codecs) {
            if (out.length() > 0) {
                out.append("\n  ");
            }
            CandidateMetrics m = benchmarkCandidate(codec.compressorBytesArray(), new byte[][]{sample}, messageType);
            out.append(String.format("%s: %dB compressor, %.1fus enc, %.1fus dec, %.2fx ratio",
                    codec.name(),
                    codec.compressorBytes(),
                    m.encodeNanos / 1000.0,
                    m.decodeNanos / 1000.0,
                    m.ratio));
        }
        return out.toString();
    }

    /**
     * Selects candidates based on actual performance metrics rather than compressor size.
     * Runs a quick benchmark on each candidate to measure encode speed, decode speed, and ratio.
     * Selects diverse candidates from the Pareto frontier of these metrics.
     */
    private static List<OpenZLCandidate> selectByPerformance(byte[][] trained,
                                                              byte[][] samples,
                                                              String messageType,
                                                              int maxCandidates) {
        if (trained.length <= 1) {
            List<OpenZLCandidate> one = new ArrayList<>(1);
            one.add(new OpenZLCandidate("openzl-trained", trained[0], 0, 0, 0));
            return one;
        }

        // Benchmark each candidate
        List<OpenZLCandidate> evaluated = new ArrayList<>(trained.length);
        byte[][] samplePayloads = selectEvaluationSamples(samples, EVALUATION_SAMPLE_COUNT);

        for (int i = 0; i < trained.length; i++) {
            byte[] compressor = trained[i];
            CandidateMetrics metrics = benchmarkCandidate(compressor, samplePayloads, messageType);
            evaluated.add(new OpenZLCandidate(
                    "openzl-trained-" + i,
                    compressor,
                    metrics.encodeNanos,
                    metrics.decodeNanos,
                    metrics.ratio));
        }

        if ("thresholds".equals(SELECTOR_MODE)) {
            return selectThresholdCandidates(evaluated, maxCandidates);
        }

        // Select diverse candidates based on performance characteristics
        return selectDiverseCandidates(evaluated, maxCandidates);
    }

    private static byte[] selectRepresentativeSample(byte[][] samples) {
        // Use median-sized sample for representative benchmarking
        List<byte[]> sorted = new ArrayList<>(Arrays.asList(samples));
        sorted.sort(Comparator.comparingInt(a -> a.length));
        return sorted.get(sorted.size() / 2);
    }

    private static byte[][] selectEvaluationSamples(byte[][] samples, int count) {
        if (samples.length <= count) {
            return samples;
        }
        byte[][] selected = new byte[count][];
        int maxIndex = samples.length - 1;
        for (int i = 0; i < count; i++) {
            int idx = (int) Math.round((double) i * maxIndex / Math.max(1, count - 1));
            selected[i] = samples[idx];
        }
        return selected;
    }

    private static CandidateMetrics benchmarkCandidate(byte[] compressor, byte[][] samples, String messageType) {
        long encodeNanosTotal = 0L;
        long decodeNanosTotal = 0L;
        long inputBytesTotal = 0L;
        long encodedBytesTotal = 0L;

        for (byte[] sample : samples) {
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                byte[] encoded = OpenZLProtobuf.convert(sample, OpenZLProtobuf.Protocol.PROTO,
                        OpenZLProtobuf.Protocol.ZL, compressor, messageType);
                OpenZLProtobuf.convert(encoded, OpenZLProtobuf.Protocol.ZL,
                        OpenZLProtobuf.Protocol.PROTO, compressor, messageType);
            }

            long encodeStart = System.nanoTime();
            byte[] encoded = null;
            for (int i = 0; i < MEASURE_ITERATIONS; i++) {
                encoded = OpenZLProtobuf.convert(sample, OpenZLProtobuf.Protocol.PROTO,
                        OpenZLProtobuf.Protocol.ZL, compressor, messageType);
            }
            long encodeNanos = (System.nanoTime() - encodeStart) / MEASURE_ITERATIONS;

            long decodeStart = System.nanoTime();
            for (int i = 0; i < MEASURE_ITERATIONS; i++) {
                OpenZLProtobuf.convert(encoded, OpenZLProtobuf.Protocol.ZL,
                        OpenZLProtobuf.Protocol.PROTO, compressor, messageType);
            }
            long decodeNanos = (System.nanoTime() - decodeStart) / MEASURE_ITERATIONS;

            encodeNanosTotal += encodeNanos;
            decodeNanosTotal += decodeNanos;
            inputBytesTotal += sample.length;
            encodedBytesTotal += (encoded == null ? sample.length : encoded.length);
        }

        long encodeNanosAvg = encodeNanosTotal / Math.max(1, samples.length);
        long decodeNanosAvg = decodeNanosTotal / Math.max(1, samples.length);
        double ratio = encodedBytesTotal > 0
                ? (double) inputBytesTotal / encodedBytesTotal
                : 1.0;

        return new CandidateMetrics(encodeNanosAvg, decodeNanosAvg, ratio);
    }

    /**
     * Selects diverse candidates covering different performance trade-offs:
     * - fastest-encode: best for write-heavy workloads
     * - fastest-decode: best for read-heavy workloads
     * - best-ratio: best for storage-constrained scenarios
     * - balanced: good overall performance (geometric mean of normalized metrics)
     * - speed-optimized: best combined encode+decode speed
     */
    private static List<OpenZLCandidate> selectDiverseCandidates(List<OpenZLCandidate> candidates,
                                                                    int maxCandidates) {
        List<OpenZLCandidate> viable = filterByMinRatio(candidates);
        if (viable.isEmpty()) {
            viable = candidates;
        }

        List<OpenZLCandidate> selected = new ArrayList<>();

        // Find best in each category
        OpenZLCandidate fastestEncode = null;
        OpenZLCandidate fastestDecode = null;
        OpenZLCandidate bestRatio = null;
        OpenZLCandidate balanced = null;
        OpenZLCandidate speedOptimized = null;

        // Compute normalization bounds
        long minEncode = Long.MAX_VALUE, maxEncode = 0;
        long minDecode = Long.MAX_VALUE, maxDecode = 0;
        double minRatio = Double.MAX_VALUE, maxRatio = 0;

        for (OpenZLCandidate c : viable) {
            if (c.encodeNanos < minEncode) minEncode = c.encodeNanos;
            if (c.encodeNanos > maxEncode) maxEncode = c.encodeNanos;
            if (c.decodeNanos < minDecode) minDecode = c.decodeNanos;
            if (c.decodeNanos > maxDecode) maxDecode = c.decodeNanos;
            if (c.ratio < minRatio) minRatio = c.ratio;
            if (c.ratio > maxRatio) maxRatio = c.ratio;
        }

        double bestBalancedScore = -1;
        double bestSpeedScore = -1;

        for (OpenZLCandidate c : viable) {
            // Fastest encode
            if (fastestEncode == null || c.encodeNanos < fastestEncode.encodeNanos) {
                fastestEncode = c;
            }
            // Fastest decode
            if (fastestDecode == null || c.decodeNanos < fastestDecode.decodeNanos) {
                fastestDecode = c;
            }
            // Best ratio
            if (bestRatio == null || c.ratio > bestRatio.ratio) {
                bestRatio = c;
            }

            // Balanced score (geometric mean of normalized metrics, higher is better)
            double encodeNorm = (maxEncode > minEncode)
                    ? 1.0 - (double)(c.encodeNanos - minEncode) / (maxEncode - minEncode)
                    : 1.0;
            double decodeNorm = (maxDecode > minDecode)
                    ? 1.0 - (double)(c.decodeNanos - minDecode) / (maxDecode - minDecode)
                    : 1.0;
            double ratioNorm = (maxRatio > minRatio)
                    ? (c.ratio - minRatio) / (maxRatio - minRatio)
                    : 1.0;

            // Balanced: equal weight to all three
            double balancedScore = Math.pow(encodeNorm * decodeNorm * ratioNorm, 1.0/3.0);
            if (balancedScore > bestBalancedScore) {
                bestBalancedScore = balancedScore;
                balanced = c;
            }

            // Speed-optimized: prioritize encode+decode speed
            double speedScore = Math.pow(encodeNorm * decodeNorm, 0.5);
            if (speedScore > bestSpeedScore) {
                bestSpeedScore = speedScore;
                speedOptimized = c;
            }
        }

        // Add candidates with meaningful names, avoiding duplicates
        addUniqueWithName(selected, fastestEncode, "openzl-trained-fast-enc");
        addUniqueWithName(selected, fastestDecode, "openzl-trained-fast-dec");
        addUniqueWithName(selected, bestRatio, "openzl-trained-best-ratio");
        addUniqueWithName(selected, balanced, "openzl-trained-balanced");
        addUniqueWithName(selected, speedOptimized, "openzl-trained-speed");

        // Trim to maxCandidates if needed
        if (maxCandidates > 0 && selected.size() > maxCandidates) {
            return new ArrayList<>(selected.subList(0, maxCandidates));
        }
        return selected;
    }

    private static List<OpenZLCandidate> selectThresholdCandidates(List<OpenZLCandidate> candidates,
                                                                   int maxCandidates) {
        List<OpenZLCandidate> viable = filterByThresholds(candidates);
        if (viable.isEmpty()) {
            viable = filterByMinRatio(candidates);
        }
        if (viable.isEmpty()) {
            viable = candidates;
        }

        List<OpenZLCandidate> selected = new ArrayList<>();

        OpenZLCandidate bestRatio = null;
        OpenZLCandidate fastestTotal = null;
        OpenZLCandidate nearestLatency = null;
        long nearestDistance = Long.MAX_VALUE;

        for (OpenZLCandidate c : viable) {
            if (bestRatio == null || c.ratio > bestRatio.ratio) {
                bestRatio = c;
            }

            if (fastestTotal == null || totalNanos(c) < totalNanos(fastestTotal)) {
                fastestTotal = c;
            }

            if (EXPECTED_TOTAL_MICROS > 0) {
                long totalMicros = totalNanos(c) / 1000L;
                long distance = Math.abs(totalMicros - EXPECTED_TOTAL_MICROS);
                if (EXPECTED_TOLERANCE_MICROS > 0 && distance > EXPECTED_TOLERANCE_MICROS) {
                    continue;
                }
                if (nearestLatency == null || distance < nearestDistance
                        || (distance == nearestDistance && c.ratio > nearestLatency.ratio)) {
                    nearestLatency = c;
                    nearestDistance = distance;
                }
            }
        }

        addUniqueWithName(selected, nearestLatency, "openzl-trained-target-latency");
        addUniqueWithName(selected, bestRatio, "openzl-trained-threshold-best-ratio");
        addUniqueWithName(selected, fastestTotal, "openzl-trained-threshold-fastest");

        if (maxCandidates > 0 && selected.size() > maxCandidates) {
            return new ArrayList<>(selected.subList(0, maxCandidates));
        }
        return selected;
    }

    private static List<OpenZLCandidate> filterByThresholds(List<OpenZLCandidate> candidates) {
        List<OpenZLCandidate> filtered = new ArrayList<>();
        for (OpenZLCandidate c : candidates) {
            if (c.ratio < MIN_COMPETITIVE_RATIO) {
                continue;
            }
            long encodeMicros = c.encodeNanos / 1000L;
            long decodeMicros = c.decodeNanos / 1000L;
            long totalMicros = (c.encodeNanos + c.decodeNanos) / 1000L;
            if (MAX_ENCODE_MICROS > 0 && encodeMicros > MAX_ENCODE_MICROS) {
                continue;
            }
            if (MAX_DECODE_MICROS > 0 && decodeMicros > MAX_DECODE_MICROS) {
                continue;
            }
            if (MAX_TOTAL_MICROS > 0 && totalMicros > MAX_TOTAL_MICROS) {
                continue;
            }
            filtered.add(c);
        }
        return filtered;
    }

    private static List<OpenZLCandidate> filterByMinRatio(List<OpenZLCandidate> candidates) {
        List<OpenZLCandidate> viable = new ArrayList<>();
        for (OpenZLCandidate c : candidates) {
            if (c.ratio >= MIN_COMPETITIVE_RATIO) {
                viable.add(c);
            }
        }
        return viable;
    }

    private static long totalNanos(OpenZLCandidate candidate) {
        return candidate.encodeNanos + candidate.decodeNanos;
    }

    private static void addUniqueWithName(List<OpenZLCandidate> target,
                                          OpenZLCandidate candidate,
                                          String name) {
        if (candidate == null) return;

        // Check if this exact compressor is already added
        for (OpenZLCandidate existing : target) {
            if (Arrays.equals(existing.bytes, candidate.bytes)) {
                return; // Already have this compressor
            }
        }

        target.add(new OpenZLCandidate(name, candidate.bytes,
                candidate.encodeNanos, candidate.decodeNanos, candidate.ratio));
    }

    private static final class CandidateMetrics {
        final long encodeNanos;
        final long decodeNanos;
        final double ratio;

        CandidateMetrics(long encodeNanos, long decodeNanos, double ratio) {
            this.encodeNanos = encodeNanos;
            this.decodeNanos = decodeNanos;
            this.ratio = ratio;
        }
    }

    private static final class OpenZLCandidate {
        final String name;
        final byte[] bytes;
        final long encodeNanos;
        final long decodeNanos;
        final double ratio;

        OpenZLCandidate(String name, byte[] bytes, long encodeNanos, long decodeNanos, double ratio) {
            this.name = name;
            this.bytes = bytes;
            this.encodeNanos = encodeNanos;
            this.decodeNanos = decodeNanos;
            this.ratio = ratio;
        }
    }
}
