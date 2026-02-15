package io.github.hybledav.bench.protobuf;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.nio.ByteBuffer;

final class Lz4Codec implements Codec {
    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    Lz4Codec() {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        this.compressor = factory.fastCompressor();
        this.decompressor = factory.fastDecompressor();
    }

    @Override
    public String name() {
        return "lz4";
    }

    @Override
    public Codec copyForThread() {
        return new Lz4Codec();
    }

    @Override
    public byte[] encode(byte[] proto) {
        int maxLen = compressor.maxCompressedLength(proto.length);
        byte[] compressed = new byte[maxLen + 4];
        ByteBuffer.wrap(compressed).putInt(proto.length);
        int written = compressor.compress(proto, 0, proto.length, compressed, 4, maxLen);
        byte[] out = new byte[written + 4];
        System.arraycopy(compressed, 0, out, 0, out.length);
        return out;
    }

    @Override
    public byte[] decode(byte[] encoded) {
        if (encoded.length < 4) {
            throw new IllegalStateException("Invalid LZ4 payload");
        }
        int original = ByteBuffer.wrap(encoded, 0, 4).getInt();
        if (original < 0) {
            throw new IllegalStateException("Invalid LZ4 original size");
        }
        byte[] out = new byte[original];
        decompressor.decompress(encoded, 4, out, 0, original);
        return out;
    }
}
