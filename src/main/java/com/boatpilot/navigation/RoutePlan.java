package com.boatpilot.navigation;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RoutePlan(Status status, List<BlockPos> path, boolean snappedFromLand) {
    public enum Status {
        READY,
        UNKNOWN,
        UNREACHABLE,
        NO_WATER_START
    }

    public RoutePlan {
        path = List.copyOf(path);
    }

    public static RoutePlan failure(Status status) {
        return new RoutePlan(status, List.of(), false);
    }
}
