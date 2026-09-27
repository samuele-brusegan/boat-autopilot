package com.boatpilot.maps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JourneyMapTileRangesTest {
    @Test
    void requestsWholeRegionsForNegativeAndPositiveChunkRanges() {
        assertEquals(java.util.List.of(
            new JourneyMapTileRanges.ChunkRange(-64, -33),
            new JourneyMapTileRanges.ChunkRange(-32, -1)),
            JourneyMapTileRanges.regions(-40, -30));
        assertEquals(java.util.List.of(
            new JourneyMapTileRanges.ChunkRange(0, 31),
            new JourneyMapTileRanges.ChunkRange(32, 63)),
            JourneyMapTileRanges.regions(16, 33));
    }
}
