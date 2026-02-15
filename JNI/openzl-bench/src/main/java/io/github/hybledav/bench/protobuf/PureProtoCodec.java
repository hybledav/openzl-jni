package io.github.hybledav.bench.protobuf;

final class PureProtoCodec implements Codec {
    @Override
    public String name() {
        return "pure";
    }

    @Override
    public byte[] encode(byte[] proto) {
        return proto;
    }

    @Override
    public byte[] decode(byte[] encoded) {
        return encoded;
    }
}
