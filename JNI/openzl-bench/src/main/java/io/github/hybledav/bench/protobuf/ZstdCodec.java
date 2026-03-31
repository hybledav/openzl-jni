package io.github.hybledav.bench.protobuf;

import com.github.luben.zstd.Zstd;

final class ZstdCodec implements Codec {
    private final int level;

    ZstdCodec(int level) {
        this.level = level;
    }

    @Override
    public String name() {
        return "zstd";
    }

    @Override
    public Codec copyForThread() {
        return new ZstdCodec(level);
    }

    @Override
    public byte[] encode(byte[] proto) {
        return Zstd.compress(proto, level);
    }

    @Override
    public byte[] decode(byte[] encoded) {
        long expected = Zstd.decompressedSize(encoded);
        if (expected <= 0 || expected > Integer.MAX_VALUE) {
            throw new IllegalStateException("Unable to determine zstd decompressed size");
        }
        return Zstd.decompress(encoded, (int) expected);
    }
}
