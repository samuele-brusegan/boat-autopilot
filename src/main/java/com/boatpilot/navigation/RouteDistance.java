package com.boatpilot.navigation;

import net.minecraft.core.BlockPos;

/** Horizontal straight-line distance used for navigation range checks. */
public final class RouteDistance {
    private RouteDistance() {
    }

    public static double horizontal(BlockPos from, BlockPos to) {
        return Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
    }
}
