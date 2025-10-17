package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestCompressorClosedBehaviour {

    @Test
    void closedCompressorArrayCompression() {
        OpenZLCompressor compressor = new OpenZLCompressor();
        compressor.close();
        byte[] input = new byte[4];
        byte[] output = new byte[128];
        assertThrows(IllegalStateException.class, () -> compressor.compress(input, output));
    }
}
