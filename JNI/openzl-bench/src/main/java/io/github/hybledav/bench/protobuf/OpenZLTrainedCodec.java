package io.github.hybledav.bench.protobuf;

import io.github.hybledav.OpenZLProtobuf;

final class OpenZLTrainedCodec implements Codec {
    private final String codecName;
    private final String messageType;
    private final byte[] compressor;

    OpenZLTrainedCodec(String codecName, String messageType, byte[] compressor) {
        this.codecName = codecName;
        this.messageType = messageType;
        this.compressor = compressor;
    }

    @Override
    public String name() {
        return codecName;
    }

    @Override
    public Codec copyForThread() {
        return new OpenZLTrainedCodec(codecName, messageType, compressor);
    }

    @Override
    public byte[] encode(byte[] proto) {
        return OpenZLProtobuf.convert(
                proto,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                compressor,
                messageType);
    }

    @Override
    public byte[] decode(byte[] encoded) {
        return OpenZLProtobuf.convert(
                encoded,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,
                compressor,
                messageType);
    }

    int compressorBytes() {
        return compressor.length;
    }

    byte[] compressorBytesArray() {
        return compressor;
    }
}
