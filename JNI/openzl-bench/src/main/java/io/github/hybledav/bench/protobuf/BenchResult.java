package io.github.hybledav.bench.protobuf;

final class BenchResult {
    final String codec;
    final double encodeMicros;
    final double decodeMicros;
    final double encodeKops;
    final double decodeKops;
    final int avgInputBytes;
    final int avgEncodedBytes;
    final double ratio;

    BenchResult(String codec,
                double encodeMicros,
                double decodeMicros,
                double encodeKops,
                double decodeKops,
                int avgInputBytes,
                int avgEncodedBytes,
                double ratio) {
        this.codec = codec;
        this.encodeMicros = encodeMicros;
        this.decodeMicros = decodeMicros;
        this.encodeKops = encodeKops;
        this.decodeKops = decodeKops;
        this.avgInputBytes = avgInputBytes;
        this.avgEncodedBytes = avgEncodedBytes;
        this.ratio = ratio;
    }
}
