package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TestProfileDiscovery {

    @Test
    void listProfilesContainsSerialAndCsv() {
        String[] profiles = OpenZLCompressor.listProfiles();
        assertNotNull(profiles);
        boolean foundSerial = Arrays.stream(profiles).anyMatch(s -> s.startsWith("serial:"));
        boolean foundCsv = Arrays.stream(profiles).anyMatch(s -> s.startsWith("csv:"));
        assertTrue(foundSerial, "Expected 'serial' profile to be present");
        assertTrue(foundCsv, "Expected 'csv' profile to be present");
    }
}
