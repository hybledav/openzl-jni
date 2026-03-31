package io.github.hybledav.bench.protobuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class GzipCodec implements Codec {
    private final int level;

    GzipCodec(int level) {
        this.level = level;
    }

    @Override
    public String name() {
        return "gzip";
    }

    @Override
    public Codec copyForThread() {
        return new GzipCodec(level);
    }

    @Override
    public byte[] encode(byte[] proto) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, proto.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out) {
            {
                def.setLevel(level);
            }
        }) {
            gzip.write(proto);
            gzip.finish();
        }
        return out.toByteArray();
    }

    @Override
    public byte[] decode(byte[] encoded) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            return in.readAllBytes();
        }
    }
}
