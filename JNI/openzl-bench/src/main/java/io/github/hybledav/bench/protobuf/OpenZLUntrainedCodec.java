package io.github.hybledav.bench.protobuf;

import io.github.hybledav.OpenZLProtobuf;

final class OpenZLUntrainedCodec implements Codec {
    private final String messageType;

    OpenZLUntrainedCodec(String messageType) {
        this.messageType = messageType;
    }

    @Override
    public String name() {
        return "openzl-untrained";
    }

    @Override
    public Codec copyForThread() {
        return new OpenZLUntrainedCodec(messageType);
    }

    @Override
    public byte[] encode(byte[] proto) {
        return OpenZLProtobuf.convert(
                proto,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                null,
                messageType);
    }

    @Override
    public byte[] decode(byte[] encoded) {
        return OpenZLProtobuf.convert(
                encoded,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,
                null,
                messageType);
    }
}
