package com.boatpilot.navigation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerTest {
    private static final int WATER = 0xff4c8fd0;
    private static final int LAND = 0xff5c9847;
    private static final int UNKNOWN = 0xff777777;
    private final RoutePlanner planner = new RoutePlanner();

    @Test
    void routesAcrossOpenWater() {
        MapRaster map = raster(8, 8, fill(64, WATER));

        RoutePlan plan = planner.plan(map, new BlockPos(1, 0, 1), new BlockPos(6, 0, 6));

        assertEquals(RoutePlan.Status.READY, plan.status());
        assertFalse(plan.path().isEmpty());
        assertEquals(1, plan.path().size());
        assertEquals(6, plan.path().getLast().getX());
        assertEquals(6, plan.path().getLast().getZ());
    }

    @Test
    void plansRoutesLongerThanOneJourneyMapTile() {
        int width = 3001;
        MapRaster map = new MapRaster(0, 0, width, 3, width, 3, fill(width * 3, WATER));

        RoutePlan plan = planner.plan(map, new BlockPos(0, 1, 1), new BlockPos(3000, 1, 1));

        assertEquals(RoutePlan.Status.READY, plan.status());
        assertTrue(plan.path().size() <= 12);
        assertEquals(3000, plan.path().getLast().getX());
    }

    @Test
    void keepsSixteenBlocksOffShoreWhenThereIsRoom() {
        int width = 64;
        int[] pixels = fill(width * width, WATER);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < width; x++) pixels[z * width + x] = LAND;
        }
        MapRaster map = new MapRaster(0, 0, width, width, width, width, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(8, 0, 20), new BlockPos(55, 0, 20));

        assertEquals(RoutePlan.Status.READY, plan.status());
        var middleOfRoute = plan.path().stream()
            .filter(pos -> pos.getX() >= 16 && pos.getX() <= 47)
            .toList();
        assertFalse(middleOfRoute.isEmpty());
        assertTrue(middleOfRoute.stream().allMatch(pos -> pos.getZ() >= 31));
    }

    @Test
    void centersRouteBetweenBothCoastsInANarrowStrait() {
        int width = 64;
        int[] pixels = fill(width * width, LAND);
        for (int z = 0; z < width; z++) {
            for (int x = 20; x <= 34; x++) pixels[z * width + x] = WATER;
        }
        MapRaster map = new MapRaster(0, 0, width, width, width, width, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(23, 0, 8), new BlockPos(30, 0, 55));

        assertEquals(RoutePlan.Status.READY, plan.status());
        assertEquals(27.0, xAtZ(new BlockPos(23, 0, 8), plan.path(), 32), 1.0);
    }

    @Test
    void routesAroundLandInsteadOfCrossingIt() {
        int[] pixels = fill(25, WATER);
        for (int z = 0; z < 4; z++) pixels[z * 5 + 2] = LAND;
        MapRaster map = raster(5, 5, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(0, 0, 2), new BlockPos(4, 0, 2));

        assertEquals(RoutePlan.Status.READY, plan.status());
        assertTrue(plan.path().stream().anyMatch(pos -> pos.getZ() == 4));
        assertTrue(plan.path().stream().allMatch(pos -> map.surface(map.pixelX(pos.getX()), map.pixelZ(pos.getZ()))
            == SurfaceType.WATER));
    }

    @Test
    void landDestinationSnapsToReachableWater() {
        int[] pixels = fill(25, WATER);
        pixels[2 * 5 + 4] = LAND;
        MapRaster map = raster(5, 5, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(0, 0, 2), new BlockPos(4, 0, 2));

        assertEquals(RoutePlan.Status.READY, plan.status());
        assertTrue(plan.snappedFromLand());
        assertEquals(3, plan.path().getLast().getX());
        assertEquals(2, plan.path().getLast().getZ());
    }

    @Test
    void locallyKnownLandFillsAnUnclassifiedMapPixelAndSnapsToWater() {
        int[] pixels = fill(25, WATER);
        pixels[2 * 5 + 4] = WaterClassifier.withLocalFallback(UNKNOWN, SurfaceType.LAND);
        MapRaster map = raster(5, 5, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(0, 0, 2), new BlockPos(4, 0, 2));

        assertEquals(RoutePlan.Status.READY, plan.status());
        assertTrue(plan.snappedFromLand());
        assertEquals(3, plan.path().getLast().getX());
        assertEquals(2, plan.path().getLast().getZ());
    }

    @Test
    void isolatedWaterIsUnreachable() {
        int[] pixels = fill(25, LAND);
        pixels[2 * 5 + 2] = WATER;
        pixels[2 * 5 + 4] = WATER;
        MapRaster map = raster(5, 5, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(2, 0, 2), new BlockPos(4, 0, 2));

        assertEquals(RoutePlan.Status.UNREACHABLE, plan.status());
    }

    @Test
    void unknownDestinationProducesUnknownStatus() {
        int[] pixels = fill(9, WATER);
        pixels[8] = UNKNOWN;
        MapRaster map = raster(3, 3, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(0, 0, 0), new BlockPos(2, 0, 2));

        assertEquals(RoutePlan.Status.UNKNOWN, plan.status());
    }

    @Test
    void willNotSqueezeDiagonallyBetweenLandBlocks() {
        int[] pixels = fill(16, LAND);
        pixels[1 * 4 + 1] = WATER;
        pixels[2 * 4 + 2] = WATER;
        MapRaster map = raster(4, 4, pixels);

        RoutePlan plan = planner.plan(map, new BlockPos(1, 0, 1), new BlockPos(2, 0, 2));

        assertEquals(RoutePlan.Status.UNREACHABLE, plan.status());
    }

    @Test
    void classifierTreatsGrayAsUnknown() {
        assertEquals(SurfaceType.WATER, WaterClassifier.classify(WATER));
        assertEquals(SurfaceType.LAND, WaterClassifier.classify(LAND));
        assertEquals(SurfaceType.LAND, WaterClassifier.classify(0xffe8dbad));
        assertEquals(SurfaceType.UNKNOWN, WaterClassifier.classify(UNKNOWN));
    }

    @Test
    void horizontalRouteDistanceUsesPythagoreanDistanceAndIgnoresHeight() {
        BlockPos start = new BlockPos(-507, 62, 300);

        assertEquals(Math.sqrt(93 * 93 + 150 * 150), RouteDistance.horizontal(start, new BlockPos(-600, 62, 450)), 0.0001);
        assertEquals(Math.sqrt(93 * 93 + 200 * 200), RouteDistance.horizontal(start, new BlockPos(-600, 62, 500)), 0.0001);
        assertEquals(RouteDistance.horizontal(start, new BlockPos(-600, 62, 500)),
            RouteDistance.horizontal(start, new BlockPos(-600, 320, 500)), 0.0001);
    }

    private static MapRaster raster(int size, int height, int[] colors) {
        return new MapRaster(0, 0, size, height, size, height, colors);
    }

    private static int[] fill(int size, int color) {
        int[] colors = new int[size];
        java.util.Arrays.fill(colors, color);
        return colors;
    }

    private static double xAtZ(BlockPos start, java.util.List<BlockPos> path, int targetZ) {
        BlockPos from = start;
        for (BlockPos to : path) {
            if (targetZ >= Math.min(from.getZ(), to.getZ()) && targetZ <= Math.max(from.getZ(), to.getZ())) {
                double portion = (targetZ - from.getZ()) / (double) (to.getZ() - from.getZ());
                return from.getX() + (to.getX() - from.getX()) * portion;
            }
            from = to;
        }
        throw new AssertionError("Route does not cross z=" + targetZ);
    }
}
