package com.boatpilot.maps;

import java.util.ArrayList;
import java.util.List;

/** Returns full JourneyMap regions touched by a requested chunk interval. */
final class JourneyMapTileRanges {
    private static final int REGION_CHUNKS = 32;

    private JourneyMapTileRanges() {
    }

    static List<ChunkRange> regions(int firstChunk, int lastChunk) {
        if (lastChunk < firstChunk) {
            throw new IllegalArgumentException("Invalid chunk range");
        }
        List<ChunkRange> ranges = new ArrayList<>();
        int regionStart = Math.floorDiv(firstChunk, REGION_CHUNKS) * REGION_CHUNKS;
        for (; regionStart <= lastChunk; regionStart += REGION_CHUNKS) {
            ranges.add(new ChunkRange(regionStart, regionStart + REGION_CHUNKS - 1));
        }
        return List.copyOf(ranges);
    }

    record ChunkRange(int first, int last) {
    }
}
