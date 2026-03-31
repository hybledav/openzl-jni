package io.github.hybledav.bench.protobuf;

import java.util.ArrayList;
import java.util.List;

final class CodecRegistry {
    private CodecRegistry() {}

    static List<Codec> defaultCodecs(String messageType,
                                     List<OpenZLTrainedCodec> trainedCandidates) {
        List<Codec> codecs = new ArrayList<>();
        codecs.add(new PureProtoCodec());
        codecs.add(new GzipCodec(6));
        codecs.add(new Lz4Codec());
        codecs.add(new ZstdCodec(3));
        codecs.add(new OpenZLUntrainedCodec(messageType));
        codecs.addAll(trainedCandidates);
        return codecs;
    }
}
