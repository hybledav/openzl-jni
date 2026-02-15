package io.github.hybledav.bench.protobuf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class BenchmarkRunner {
    private final int warmupIterations;
    private final int measureIterations;
    private final int threads;

    BenchmarkRunner(int warmupIterations, int measureIterations, int threads) {
        this.warmupIterations = warmupIterations;
        this.measureIterations = measureIterations;
        this.threads = Math.max(1, threads);
    }

    BenchResult run(Codec codec, List<byte[]> payloads) throws Exception {
        if (threads <= 1) {
            return runSingleThread(codec, payloads);
        }
        return runMultiThread(codec, payloads);
    }

    private BenchResult runSingleThread(Codec codec, List<byte[]> payloads) throws Exception {
        for (int i = 0; i < warmupIterations; ++i) {
            byte[] proto = payloads.get(i % payloads.size());
            byte[] encoded = codec.encode(proto);
            codec.decode(encoded);
        }

        long encodeNs = 0L;
        long decodeNs = 0L;
        long totalInput = 0L;
        long totalEncoded = 0L;

        List<byte[]> encodedPayloads = new ArrayList<>(measureIterations);

        for (int i = 0; i < measureIterations; ++i) {
            byte[] proto = payloads.get(i % payloads.size());
            long startEncode = System.nanoTime();
            byte[] encoded = codec.encode(proto);
            encodeNs += System.nanoTime() - startEncode;

            encodedPayloads.add(encoded);
            totalInput += proto.length;
            totalEncoded += encoded.length;
        }

        for (int i = 0; i < measureIterations; ++i) {
            byte[] encoded = encodedPayloads.get(i);
            long startDecode = System.nanoTime();
            byte[] decoded = codec.decode(encoded);
            decodeNs += System.nanoTime() - startDecode;
            if (decoded == null || decoded.length == 0) {
                throw new IllegalStateException("Decoded payload is empty for codec " + codec.name());
            }
        }

        double encodeMicros = (encodeNs / 1_000.0) / measureIterations;
        double decodeMicros = (decodeNs / 1_000.0) / measureIterations;
        double encodeKops = (measureIterations * 1_000_000.0) / encodeNs;
        double decodeKops = (measureIterations * 1_000_000.0) / decodeNs;

        int avgInput = (int) (totalInput / measureIterations);
        int avgEncoded = (int) (totalEncoded / measureIterations);
        double ratio = avgEncoded == 0 ? 0.0 : (double) avgInput / (double) avgEncoded;

        return new BenchResult(codec.name(),
                encodeMicros,
                decodeMicros,
                encodeKops,
                decodeKops,
                avgInput,
                avgEncoded,
                ratio);
    }

    private BenchResult runMultiThread(Codec codec, List<byte[]> payloads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            runWarmupParallel(codec, payloads, pool);

            List<Future<ThreadResult>> encodeFutures = submitJobs(pool, codec, payloads, true);
            Aggregate encode = collect(encodeFutures);

            List<Future<ThreadResult>> decodeFutures = submitJobs(pool, codec, payloads, false);
            Aggregate decode = collect(decodeFutures);

            double encodeMicros = (encode.totalNs / 1_000.0) / encode.totalOps;
            double decodeMicros = (decode.totalNs / 1_000.0) / decode.totalOps;
            double encodeKops = (encode.totalOps * 1_000_000.0) / encode.totalNs;
            double decodeKops = (decode.totalOps * 1_000_000.0) / decode.totalNs;

            int avgInput = (int) (encode.totalInputBytes / encode.totalOps);
            int avgEncoded = (int) (encode.totalEncodedBytes / encode.totalOps);
            double ratio = avgEncoded == 0 ? 0.0 : (double) avgInput / (double) avgEncoded;

            return new BenchResult(codec.name(),
                    encodeMicros,
                    decodeMicros,
                    encodeKops,
                    decodeKops,
                    avgInput,
                    avgEncoded,
                    ratio);
        } finally {
            pool.shutdownNow();
        }
    }

    private void runWarmupParallel(Codec codec, List<byte[]> payloads, ExecutorService pool) throws Exception {
        List<Future<Void>> futures = new ArrayList<>(threads);
        int perThreadWarmup = Math.max(1, warmupIterations / threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                Codec local = codec.copyForThread();
                int size = payloads.size();
                int offset = (threadId * size) / Math.max(1, threads);
                for (int i = 0; i < perThreadWarmup; i++) {
                    byte[] proto = payloads.get((offset + i) % size);
                    byte[] encoded = local.encode(proto);
                    local.decode(encoded);
                }
                return null;
            }));
        }
        for (Future<Void> future : futures) {
            future.get();
        }
    }

    private List<Future<ThreadResult>> submitJobs(ExecutorService pool,
                                                  Codec codec,
                                                  List<byte[]> payloads,
                                                  boolean encode) {
        List<Future<ThreadResult>> futures = new ArrayList<>(threads);
        int opsPerThread = Math.max(1, measureIterations / threads);
        int remainder = Math.max(0, measureIterations - (opsPerThread * threads));
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            final int ops = opsPerThread + (t < remainder ? 1 : 0);
            futures.add(pool.submit(new CodecTask(codec, payloads, threadId, threads, ops, encode)));
        }
        return futures;
    }

    private Aggregate collect(List<Future<ThreadResult>> futures) throws Exception {
        Aggregate aggregate = new Aggregate();
        for (Future<ThreadResult> future : futures) {
            ThreadResult result;
            try {
                result = future.get();
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new RuntimeException(cause);
            }
            aggregate.totalNs += result.totalNs;
            aggregate.totalOps += result.ops;
            aggregate.totalInputBytes += result.totalInputBytes;
            aggregate.totalEncodedBytes += result.totalEncodedBytes;
        }
        return aggregate;
    }

    private static final class CodecTask implements Callable<ThreadResult> {
        private final Codec codec;
        private final List<byte[]> payloads;
        private final int threadId;
        private final int threadCount;
        private final int ops;
        private final boolean encode;

        private CodecTask(Codec codec,
                          List<byte[]> payloads,
                          int threadId,
                          int threadCount,
                          int ops,
                          boolean encode) {
            this.codec = codec;
            this.payloads = payloads;
            this.threadId = threadId;
            this.threadCount = threadCount;
            this.ops = ops;
            this.encode = encode;
        }

        @Override
        public ThreadResult call() throws Exception {
            Codec local = codec.copyForThread();
            int size = payloads.size();
            int offset = (threadId * size) / Math.max(1, threadCount);

            long totalNs = 0L;
            long totalInput = 0L;
            long totalEncoded = 0L;

            if (encode) {
                for (int i = 0; i < ops; i++) {
                    byte[] proto = payloads.get((offset + i) % size);
                    long start = System.nanoTime();
                    byte[] encoded = local.encode(proto);
                    totalNs += System.nanoTime() - start;
                    totalInput += proto.length;
                    totalEncoded += encoded.length;
                }
            } else {
                for (int i = 0; i < ops; i++) {
                    byte[] proto = payloads.get((offset + i) % size);
                    byte[] encoded = local.encode(proto);
                    long start = System.nanoTime();
                    byte[] decoded = local.decode(encoded);
                    totalNs += System.nanoTime() - start;
                    if (decoded == null || decoded.length == 0) {
                        throw new IllegalStateException("Decoded payload is empty for codec " + local.name());
                    }
                }
            }

            return new ThreadResult(totalNs, ops, totalInput, totalEncoded);
        }
    }

    private static final class ThreadResult {
        final long totalNs;
        final long ops;
        final long totalInputBytes;
        final long totalEncodedBytes;

        private ThreadResult(long totalNs,
                             long ops,
                             long totalInputBytes,
                             long totalEncodedBytes) {
            this.totalNs = totalNs;
            this.ops = ops;
            this.totalInputBytes = totalInputBytes;
            this.totalEncodedBytes = totalEncodedBytes;
        }
    }

    private static final class Aggregate {
        long totalNs;
        long totalOps;
        long totalInputBytes;
        long totalEncodedBytes;
    }
}
