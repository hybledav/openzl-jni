package io.github.hybledav.bench.protobuf;

import java.util.ArrayList;
import java.util.List;

final class DynamicBenchPayloadGenerator {
    private DynamicBenchPayloadGenerator() {}

    static List<byte[]> makePayloads(byte[] sao, int payloadCount, int chunkSize) {
        List<byte[]> payloads = new ArrayList<>(payloadCount);
        for (int i = 0; i < payloadCount; ++i) {
            payloads.add(BenchSchema.buildProtoPayload(sao, i * 7919, chunkSize));
        }
        return payloads;
    }
}
