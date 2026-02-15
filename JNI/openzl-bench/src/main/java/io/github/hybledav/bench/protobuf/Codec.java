package io.github.hybledav.bench.protobuf;

interface Codec {
    String name();

    default Codec copyForThread() {
        return this;
    }

    byte[] encode(byte[] proto) throws Exception;

    byte[] decode(byte[] encoded) throws Exception;
}
