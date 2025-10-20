package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestOpenZLGraphMapping {

    @Test
    void knownIdMapsToGraph() {
        assertEquals(OpenZLGraph.ZSTD, OpenZLGraph.fromNativeId(OpenZLGraph.ZSTD.id()));
    }

    @Test
    void unknownIdFallsBackToAuto() {
        assertEquals(OpenZLGraph.AUTO, OpenZLGraph.fromNativeId(9999));
    }
}
