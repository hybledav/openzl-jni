package io.github.hybledav.bench.protobuf;

import io.github.hybledav.OpenZLProtobuf;
import io.github.hybledav.TrainOptions;
import io.github.hybledav.utils.sao.SaoDataLoader;
import io.github.hybledav.utils.sao.SaoPayloadGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProtobufCodecBenchMain {
    private ProtobufCodecBenchMain() {}

    public static void main(String[] args) throws Exception {
        int payloadCount = Integer.getInteger("bench.payload.count", 256);
        int payloadChunkBytes = Integer.getInteger("bench.payload.chunk.bytes", 4096);
        String payloadMode = System.getProperty("bench.payload.mode", "sao").trim().toLowerCase(Locale.ROOT);
        int warmup = Integer.getInteger("bench.warmup.iterations", 1000);
        int measure = Integer.getInteger("bench.measure.iterations", 10000);
        int threads = Integer.getInteger("bench.threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        int openzlCandidateCount = Integer.getInteger("bench.openzl.candidates", 5);
        double weightEncode = Double.parseDouble(System.getProperty("bench.rank.weight.encode", "0.45"));
        double weightDecode = Double.parseDouble(System.getProperty("bench.rank.weight.decode", "0.25"));
        double weightRatio = Double.parseDouble(System.getProperty("bench.rank.weight.ratio", "0.30"));
        boolean inspectOpenzl = Boolean.parseBoolean(System.getProperty("bench.openzl.inspect", "false"));
        String inspectDir = System.getProperty("bench.openzl.inspect.dir", ".bench-logs/openzl-graphs");
        String saoPath = System.getProperty("sao.path");

        byte[] sao = SaoDataLoader.loadRaw(saoPath);
        List<byte[]> payloads;
        String messageType;
        switch (payloadMode) {
            case "dynamic":
                payloads = DynamicBenchPayloadGenerator.makePayloads(sao, payloadCount, payloadChunkBytes);
                OpenZLProtobuf.registerSchema(BenchSchema.descriptorBytes());
                messageType = BenchSchema.messageType();
                break;
            case "sao":
            default:
                // Columnar: groups all RA values, all DEC values, etc. together
                // This artificially improves compressibility (~2.34x)
                payloads = SaoPayloadGenerator.makePayloads(sao, payloadCount, payloadChunkBytes);
                OpenZLProtobuf.registerSchema(SaoPayloadGenerator.descriptorBytes());
                messageType = SaoPayloadGenerator.messageType();
                break;
        }

        TrainOptions opts = new TrainOptions();
        // Increased default from 30s to 120s to allow better model convergence
        // Training was frequently stopping early before finding optimal graphs
        opts.maxTimeSecs = Integer.getInteger("bench.openzl.train.max.secs", 120);
        opts.threads = Integer.getInteger("bench.openzl.train.threads", 4);
        opts.numSamples = Integer.getInteger("bench.openzl.train.samples", payloadCount);
        // Enable Pareto frontier by default to get diverse candidates for selection
        opts.paretoFrontier = Boolean.parseBoolean(System.getProperty("bench.openzl.train.pareto", "true"));

        byte[][] trainingSamples = payloads.toArray(new byte[0][]);

        long trainStart = System.nanoTime();
        List<OpenZLTrainedCodec> trainedCandidates = OpenZLTrainer.trainCandidateCodecs(
                messageType,
                trainingSamples,
                opts,
                openzlCandidateCount);
        List<Codec> codecs = CodecRegistry.defaultCodecs(messageType, trainedCandidates);
        long trainNs = System.nanoTime() - trainStart;

        System.out.printf(Locale.ROOT,
                "mode=%s, SAO=%d B, payloads=%d, avgPayload=%d B, warmup=%d, measure=%d, threads=%d%n",
                payloadMode,
                sao.length,
                payloads.size(),
                averageSize(payloads),
                warmup,
                measure,
                threads);
        System.out.printf(Locale.ROOT,
                "OpenZL training: %.2f ms, candidates=%d%n  %s%n",
                trainNs / 1_000_000.0,
                trainedCandidates.size(),
                OpenZLTrainer.summarizeWithMetrics(trainedCandidates, trainingSamples, messageType));

        BenchmarkRunner runner = new BenchmarkRunner(warmup, measure, threads);
        List<BenchResult> results = new ArrayList<>();
        for (Codec codec : codecs) {
            results.add(runner.run(codec, payloads));
        }

        results.sort(Comparator.comparing(r -> r.codec));
        printTable(results);
        for (OpenZLTrainedCodec codec : trainedCandidates) {
            printRelative(results, codec.name(), "openzl-untrained");
            printRelative(results, codec.name(), "gzip-6");
            printRelative(results, codec.name(), "zstd-3");
            printRelative(results, codec.name(), "lz4");
        }
        printBalancedRanking(results, weightEncode, weightDecode, weightRatio);
        if (inspectOpenzl) {
            inspectOpenzlCandidates(trainedCandidates, inspectDir);
        }
    }

    private static int averageSize(List<byte[]> payloads) {
        long sum = 0L;
        for (byte[] payload : payloads) {
            sum += payload.length;
        }
        return payloads.isEmpty() ? 0 : (int) (sum / payloads.size());
    }

    private static void printTable(List<BenchResult> results) {
        System.out.println();
        System.out.println("codec            | encode_us | decode_us | encode_kops | decode_kops | in_B | out_B | ratio");
        System.out.println("-----------------+-----------+-----------+-------------+-------------+------+-------+------");
        for (BenchResult r : results) {
            System.out.printf(Locale.ROOT,
                    "%-16s | %9.3f | %9.3f | %11.2f | %11.2f | %4d | %5d | %4.2f%n",
                    r.codec,
                    r.encodeMicros,
                    r.decodeMicros,
                    r.encodeKops,
                    r.decodeKops,
                    r.avgInputBytes,
                    r.avgEncodedBytes,
                    r.ratio);
        }
    }

    private static void printRelative(List<BenchResult> results, String left, String right) {
        BenchResult a = find(results, left);
        BenchResult b = find(results, right);
        if (a == null || b == null) {
            return;
        }
        double encodeGain = pctFaster(a.encodeMicros, b.encodeMicros);
        double decodeGain = pctFaster(a.decodeMicros, b.decodeMicros);
        double ratioGain = pctBetter(a.ratio, b.ratio);

        System.out.printf(Locale.ROOT,
                "%n%s vs %s: encode %.2f%%, decode %.2f%%, ratio %.2f%%%n",
                left,
                right,
                encodeGain,
                decodeGain,
                ratioGain);
    }

    private static BenchResult find(List<BenchResult> results, String codec) {
        for (BenchResult r : results) {
            if (r.codec.equals(codec)) {
                return r;
            }
        }
        return null;
    }

    private static double pctFaster(double aMicros, double bMicros) {
        if (bMicros == 0.0) {
            return 0.0;
        }
        return ((bMicros - aMicros) / bMicros) * 100.0;
    }

    private static double pctBetter(double a, double b) {
        if (b == 0.0) {
            return 0.0;
        }
        return ((a - b) / b) * 100.0;
    }

    private static void printBalancedRanking(List<BenchResult> results,
                                              double weightEncode,
                                              double weightDecode,
                                              double weightRatio) {
        double sum = weightEncode + weightDecode + weightRatio;
        if (sum <= 0.0) {
            System.out.println("\nBalanced ranking skipped (invalid weights). ");
            return;
        }
        double wE = weightEncode / sum;
        double wD = weightDecode / sum;
        double wR = weightRatio / sum;

        // Filter out 'pure' codec from normalization - it's a no-op baseline
        // that skews the normalization with near-zero latencies
        List<BenchResult> realCodecs = new ArrayList<>();
        BenchResult pureResult = null;
        for (BenchResult r : results) {
            if ("pure".equals(r.codec)) {
                pureResult = r;
            } else {
                realCodecs.add(r);
            }
        }

        if (realCodecs.isEmpty()) {
            System.out.println("\nBalanced ranking skipped (no real codecs to compare). ");
            return;
        }

        // Compute normalization bounds from real codecs only
        double minEncode = Double.MAX_VALUE;
        double minDecode = Double.MAX_VALUE;
        double maxRatio = 0.0;
        for (BenchResult r : realCodecs) {
            if (r.encodeMicros < minEncode) {
                minEncode = r.encodeMicros;
            }
            if (r.decodeMicros < minDecode) {
                minDecode = r.decodeMicros;
            }
            if (r.ratio > maxRatio) {
                maxRatio = r.ratio;
            }
        }

        if (minEncode <= 0.0 || minDecode <= 0.0 || maxRatio <= 0.0) {
            System.out.println("\nBalanced ranking skipped (invalid metric baseline). ");
            return;
        }

        List<RankedCodec> ranking = new ArrayList<>(realCodecs.size());
        for (BenchResult r : realCodecs) {
            double encodeNorm = minEncode / Math.max(r.encodeMicros, 1e-12);
            double decodeNorm = minDecode / Math.max(r.decodeMicros, 1e-12);
            double ratioNorm = r.ratio / maxRatio;
            double score = Math.pow(encodeNorm, wE)
                    * Math.pow(decodeNorm, wD)
                    * Math.pow(ratioNorm, wR);
            ranking.add(new RankedCodec(r.codec, score, encodeNorm, decodeNorm, ratioNorm));
        }
        ranking.sort((a, b) -> Double.compare(b.score, a.score));

        System.out.printf(Locale.ROOT,
                "%nBalanced ranking (weights encode=%.2f decode=%.2f ratio=%.2f)%n",
                wE,
                wD,
                wR);
        System.out.println("rank | codec            | score  | enc_norm | dec_norm | ratio_norm");
        System.out.println("-----+------------------+--------+----------+----------+-----------");
        int rank = 1;
        for (RankedCodec r : ranking) {
            System.out.printf(Locale.ROOT,
                    "%4d | %-16s | %6.3f | %8.3f | %8.3f | %9.3f%n",
                    rank++,
                    r.codec,
                    r.score,
                    r.encodeNorm,
                    r.decodeNorm,
                    r.ratioNorm);
        }

        // Print pure codec separately as baseline reference
        if (pureResult != null) {
            System.out.printf(Locale.ROOT,
                    "%n(baseline) pure: %.3f us encode, %.3f us decode, ratio %.2f%n",
                    pureResult.encodeMicros,
                    pureResult.decodeMicros,
                    pureResult.ratio);
        }
    }

    private static final class RankedCodec {
        final String codec;
        final double score;
        final double encodeNorm;
        final double decodeNorm;
        final double ratioNorm;

        private RankedCodec(String codec,
                            double score,
                            double encodeNorm,
                            double decodeNorm,
                            double ratioNorm) {
            this.codec = codec;
            this.score = score;
            this.encodeNorm = encodeNorm;
            this.decodeNorm = decodeNorm;
            this.ratioNorm = ratioNorm;
        }
    }

    private static void inspectOpenzlCandidates(List<OpenZLTrainedCodec> candidates, String dir) throws Exception {
        Path outDir = Path.of(dir);
        Files.createDirectories(outDir);
        System.out.println("\nOpenZL candidate graph inspection:");
        for (OpenZLTrainedCodec codec : candidates) {
            byte[] compressor = codec.compressorBytesArray();
            String graphJson = OpenZLProtobuf.graphJsonFromCompressor(compressor);
            Path compressorPath = outDir.resolve(codec.name() + ".bin");
            Path graphPath = outDir.resolve(codec.name() + ".graph.json");
            Files.write(compressorPath, compressor);
            Files.writeString(graphPath, graphJson);
            String summary = summarizeGraph(graphJson);
            System.out.printf(Locale.ROOT,
                    "- %s: compressor=%dB, graph=%s, %s%n",
                    codec.name(),
                    codec.compressorBytes(),
                    graphPath,
                    summary);
        }
    }

    private static String summarizeGraph(String json) {
        String lower = json.toLowerCase(Locale.ROOT);
        int nodeCount = countToken(lower, "\"nodes\"");
        int edgeCount = countToken(lower, "\"edges\"");
        int zstd = countToken(lower, "zstd");
        int huffman = countToken(lower, "huffman");
        int fse = countToken(lower, "fse");
        int bitpack = countToken(lower, "bitpack");
        int entropy = countToken(lower, "entropy");
        int ace = countToken(lower, "ace");
        return String.format(Locale.ROOT,
                "nodes=%d edges=%d tokens[zstd=%d huffman=%d fse=%d bitpack=%d entropy=%d ace=%d]",
                nodeCount,
                edgeCount,
                zstd,
                huffman,
                fse,
                bitpack,
                entropy,
                ace);
    }

    private static int countToken(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + Math.max(1, token.length());
        }
    }
}
