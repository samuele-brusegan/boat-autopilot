package com.boatpilot.navigation;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Finds a route through image-classified water without cutting diagonal land corners. */
public final class RoutePlanner {
    private static final int[][] NEIGHBORS = {
        {-1, -1}, {0, -1}, {1, -1},
        {-1, 0},             {1, 0},
        {-1, 1},  {0, 1},   {1, 1}
    };
    private static final int MAX_LAND_SNAP_BLOCKS = 64;
    private static final int MAX_SMOOTHING_LOOKAHEAD = 256;
    private static final int BLOCK_COST = 10;
    private static final int DIAGONAL_COST = 14;
    private static final int CLEARANCE_BLOCKS = 16;
    private static final int CLEARANCE_SCORE_PER_BLOCK = 3;
    private static final int CLEARANCE_LIMIT = CLEARANCE_BLOCKS * CLEARANCE_SCORE_PER_BLOCK;
    private static final int STRAIGHTENING_CLEARANCE_TOLERANCE = CLEARANCE_SCORE_PER_BLOCK;

    public RoutePlan plan(MapRaster map, BlockPos start, BlockPos destination) {
        int sx = map.pixelX(start.getX());
        int sz = map.pixelZ(start.getZ());
        int tx = map.pixelX(destination.getX());
        int tz = map.pixelZ(destination.getZ());
        if (sx < 0 || sz < 0 || tx < 0 || tz < 0) {
            return RoutePlan.failure(RoutePlan.Status.UNKNOWN);
        }
        if (map.surface(sx, sz) == SurfaceType.UNKNOWN || map.surface(tx, tz) == SurfaceType.UNKNOWN) {
            return RoutePlan.failure(RoutePlan.Status.UNKNOWN);
        }
        if (map.surface(sx, sz) != SurfaceType.WATER) {
            return RoutePlan.failure(RoutePlan.Status.NO_WATER_START);
        }

        int width = map.width();
        int size = width * map.height();
        byte[] clearance = calculateClearance(map);
        int[] previous = new int[size];
        java.util.Arrays.fill(previous, -2);
        int[] costs = new int[size];
        java.util.Arrays.fill(costs, Integer.MAX_VALUE);
        boolean[] closed = new boolean[size];
        PriorityQueue<SearchNode> queue = new PriorityQueue<>(Comparator
            .comparingInt(SearchNode::estimatedTotalCost)
            .thenComparingInt(SearchNode::heuristic)
            .thenComparing(Comparator.comparingInt(SearchNode::clearanceScore).reversed()));
        int source = sz * width + sx;
        previous[source] = -1;
        costs[source] = 0;
        boolean waterDestination = map.surface(tx, tz) == SurfaceType.WATER;
        int snapRadius = waterDestination ? 0 : MAX_LAND_SNAP_BLOCKS;
        queue.add(searchNode(source, 0, sx, sz, tx, tz, snapRadius, clearance[source] & 0xff));
        boolean unknownFrontier = false;
        int target = tz * width + tx;

        while (!queue.isEmpty()) {
            SearchNode entry = queue.remove();
            int current = entry.index();
            if (entry.cost() != costs[current] || closed[current]) continue;
            closed[current] = true;
            int x = current % width;
            int z = current / width;
            if (map.surface(x, z) == SurfaceType.WATER) {
                long distance = squaredBlockDistance(map, x, z, destination);
                if (current == target) {
                    return readyPlan(map, clearance, previous, source, target, false);
                }
                if (!waterDestination
                    && distance <= (long) MAX_LAND_SNAP_BLOCKS * MAX_LAND_SNAP_BLOCKS) {
                    int snapTarget = nearestConnectedWater(map, destination, current, previous);
                    return readyPlan(map, clearance, previous, source, snapTarget, true);
                }
            }

            for (int[] offset : NEIGHBORS) {
                int nx = x + offset[0];
                int nz = z + offset[1];
                if (nx < 0 || nz < 0 || nx >= width || nz >= map.height()) {
                    unknownFrontier = true;
                    continue;
                }
                SurfaceType surface = map.surface(nx, nz);
                if (surface == SurfaceType.UNKNOWN) {
                    unknownFrontier = true;
                    continue;
                }
                if (surface != SurfaceType.WATER) continue;
                if (offset[0] != 0 && offset[1] != 0
                    && (map.surface(x + offset[0], z) != SurfaceType.WATER
                    || map.surface(x, z + offset[1]) != SurfaceType.WATER)) continue;
                int next = nz * width + nx;
                int moveCost = offset[0] != 0 && offset[1] != 0 ? DIAGONAL_COST : BLOCK_COST;
                int nextClearance = clearance[next] & 0xff;
                int tentativeCost = costs[current] + moveCost + clearancePenalty(nextClearance);
                if (!closed[next] && tentativeCost < costs[next]) {
                    costs[next] = tentativeCost;
                    previous[next] = current;
                    queue.add(searchNode(next, tentativeCost, nx, nz, tx, tz, snapRadius, nextClearance));
                }
            }
        }

        if (map.surface(tx, tz) == SurfaceType.WATER) {
            return RoutePlan.failure(unknownFrontier ? RoutePlan.Status.UNKNOWN : RoutePlan.Status.UNREACHABLE);
        }
        return RoutePlan.failure(unknownFrontier ? RoutePlan.Status.UNKNOWN : RoutePlan.Status.UNREACHABLE);
    }

    private static long squaredBlockDistance(MapRaster map, int x, int z, BlockPos destination) {
        long dx = map.blockX(x) - destination.getX();
        long dz = map.blockZ(z) - destination.getZ();
        return dx * dx + dz * dz;
    }

    private static RoutePlan readyPlan(MapRaster map, byte[] clearance, int[] previous,
                                      int source, int target, boolean snapped) {
        List<BlockPos> fullPath = new ArrayList<>();
        for (int cursor = target; cursor != -1; cursor = previous[cursor]) {
            int x = cursor % map.width();
            int z = cursor / map.width();
            fullPath.add(new BlockPos(map.blockX(x), 0, map.blockZ(z)));
        }
        Collections.reverse(fullPath);
        return new RoutePlan(RoutePlan.Status.READY, smoothPath(map, clearance, fullPath), snapped);
    }

    /** Chamfer distance transform: 3 per orthogonal block, 4 per diagonal block, capped at 16 blocks. */
    private static byte[] calculateClearance(MapRaster map) {
        int width = map.width();
        int height = map.height();
        byte[] distances = new byte[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                if (map.surface(x, z) != SurfaceType.WATER) {
                    distances[index] = 0;
                    continue;
                }
                int edge = Math.min(Math.min(x + 1, width - x), Math.min(z + 1, height - z));
                distances[index] = (byte) Math.min(CLEARANCE_LIMIT, edge * CLEARANCE_SCORE_PER_BLOCK);
            }
        }
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                int score = distances[index] & 0xff;
                if (x > 0) score = Math.min(score, scoreAt(distances, index - 1) + CLEARANCE_SCORE_PER_BLOCK);
                if (z > 0) score = Math.min(score, scoreAt(distances, index - width) + CLEARANCE_SCORE_PER_BLOCK);
                if (x > 0 && z > 0) score = Math.min(score, scoreAt(distances, index - width - 1) + 4);
                if (x + 1 < width && z > 0) score = Math.min(score, scoreAt(distances, index - width + 1) + 4);
                distances[index] = (byte) Math.min(CLEARANCE_LIMIT, score);
            }
        }
        for (int z = height - 1; z >= 0; z--) {
            for (int x = width - 1; x >= 0; x--) {
                int index = z * width + x;
                int score = distances[index] & 0xff;
                if (x + 1 < width) score = Math.min(score, scoreAt(distances, index + 1) + CLEARANCE_SCORE_PER_BLOCK);
                if (z + 1 < height) score = Math.min(score, scoreAt(distances, index + width) + CLEARANCE_SCORE_PER_BLOCK);
                if (x + 1 < width && z + 1 < height) score = Math.min(score, scoreAt(distances, index + width + 1) + 4);
                if (x > 0 && z + 1 < height) score = Math.min(score, scoreAt(distances, index + width - 1) + 4);
                distances[index] = (byte) Math.min(CLEARANCE_LIMIT, score);
            }
        }
        return distances;
    }

    private static int scoreAt(byte[] distances, int index) {
        return distances[index] & 0xff;
    }

    private static int clearancePenalty(int clearanceScore) {
        int deficit = Math.max(0, CLEARANCE_LIMIT - clearanceScore);
        return deficit * 8;
    }

    /** Selects the closest water cell to a land destination within the boat's reachable water patch. */
    private static int nearestConnectedWater(MapRaster map, BlockPos destination, int reachableSeed,
                                             int[] routePrevious) {
        int centerX = map.pixelX(destination.getX());
        int centerZ = map.pixelZ(destination.getZ());
        int radiusX = (int) Math.ceil(MAX_LAND_SNAP_BLOCKS * map.width() / (double) map.blockSpanX()) + 1;
        int radiusZ = (int) Math.ceil(MAX_LAND_SNAP_BLOCKS * map.height() / (double) map.blockSpanZ()) + 1;
        int minX = Math.max(0, centerX - radiusX);
        int maxX = Math.min(map.width() - 1, centerX + radiusX);
        int minZ = Math.max(0, centerZ - radiusZ);
        int maxZ = Math.min(map.height() - 1, centerZ + radiusZ);
        int localWidth = maxX - minX + 1;
        int localHeight = maxZ - minZ + 1;
        int seedX = reachableSeed % map.width();
        int seedZ = reachableSeed / map.width();
        int localSeed = (seedZ - minZ) * localWidth + seedX - minX;
        int[] localPrevious = new int[localWidth * localHeight];
        Arrays.fill(localPrevious, -2);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        localPrevious[localSeed] = -1;
        queue.add(localSeed);
        int closest = localSeed;
        long closestDistance = squaredBlockDistance(map, seedX, seedZ, destination);

        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = minX + current % localWidth;
            int z = minZ + current / localWidth;
            long distance = squaredBlockDistance(map, x, z, destination);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = current;
            }
            for (int[] offset : NEIGHBORS) {
                int nx = x + offset[0];
                int nz = z + offset[1];
                if (nx < minX || nz < minZ || nx > maxX || nz > maxZ
                    || map.surface(nx, nz) != SurfaceType.WATER) continue;
                if (offset[0] != 0 && offset[1] != 0
                    && (map.surface(x + offset[0], z) != SurfaceType.WATER
                    || map.surface(x, z + offset[1]) != SurfaceType.WATER)) continue;
                int localNext = (nz - minZ) * localWidth + nx - minX;
                if (localPrevious[localNext] == -2) {
                    localPrevious[localNext] = current;
                    queue.addLast(localNext);
                }
            }
        }

        for (int cursor = closest; cursor != localSeed; cursor = localPrevious[cursor]) {
            int x = minX + cursor % localWidth;
            int z = minZ + cursor / localWidth;
            int parent = localPrevious[cursor];
            int parentX = minX + parent % localWidth;
            int parentZ = minZ + parent / localWidth;
            routePrevious[z * map.width() + x] = parentZ * map.width() + parentX;
        }
        int closestX = minX + closest % localWidth;
        int closestZ = minZ + closest / localWidth;
        return closestZ * map.width() + closestX;
    }

    private static List<BlockPos> smoothPath(MapRaster map, byte[] clearance, List<BlockPos> path) {
        if (path.size() <= 1) return List.of();
        List<BlockPos> smoothed = new ArrayList<>();
        int anchor = 0;
        while (anchor < path.size() - 1) {
            int candidate = Math.min(path.size() - 1, anchor + MAX_SMOOTHING_LOOKAHEAD);
            while (candidate > anchor + 1 && !lineIsNoWorse(map, clearance, path, anchor, candidate)) {
                candidate--;
            }
            smoothed.add(path.get(candidate));
            anchor = candidate;
        }
        return smoothed;
    }

    /** Bresenham shortcut test; it cannot cut land and preserves useful coast clearance. */
    private static boolean lineIsNoWorse(MapRaster map, byte[] clearance, List<BlockPos> path,
                                         int anchor, int candidate) {
        BlockPos from = path.get(anchor);
        BlockPos to = path.get(candidate);
        int x = map.pixelX(from.getX());
        int z = map.pixelZ(from.getZ());
        int endX = map.pixelX(to.getX());
        int endZ = map.pixelZ(to.getZ());
        if (x < 0 || z < 0 || endX < 0 || endZ < 0) return false;
        List<Integer> originalClearance = new ArrayList<>();
        for (int i = anchor + 1; i <= candidate; i++) {
            int pathX = map.pixelX(path.get(i).getX());
            int pathZ = map.pixelZ(path.get(i).getZ());
            originalClearance.add(clearance[pathZ * map.width() + pathX] & 0xff);
        }
        List<Integer> shortcutClearance = new ArrayList<>();
        int deltaX = Math.abs(endX - x);
        int deltaZ = Math.abs(endZ - z);
        int stepX = Integer.compare(endX, x);
        int stepZ = Integer.compare(endZ, z);
        int error = deltaX - deltaZ;

        while (x != endX || z != endZ) {
            int doubledError = 2 * error;
            int oldX = x;
            int oldZ = z;
            if (doubledError > -deltaZ) {
                error -= deltaZ;
                x += stepX;
            }
            if (doubledError < deltaX) {
                error += deltaX;
                z += stepZ;
            }
            if (x != oldX && z != oldZ
                && (map.surface(x, oldZ) != SurfaceType.WATER
                || map.surface(oldX, z) != SurfaceType.WATER)) return false;
            if (map.surface(x, z) != SurfaceType.WATER) return false;
            shortcutClearance.add(clearance[z * map.width() + x] & 0xff);
        }
        if (shortcutClearance.isEmpty() || originalClearance.isEmpty()) return true;
        for (int i = 0; i < shortcutClearance.size(); i++) {
            int originalIndex = shortcutClearance.size() == 1
                ? originalClearance.size() - 1
                : (int) Math.round(i * (originalClearance.size() - 1.0) / (shortcutClearance.size() - 1.0));
            int originalScore = originalClearance.get(originalIndex);
            int shortcutScore = shortcutClearance.get(i);
            if (originalScore >= CLEARANCE_LIMIT) {
                if (shortcutScore + STRAIGHTENING_CLEARANCE_TOLERANCE < CLEARANCE_LIMIT) return false;
            } else if (shortcutScore + STRAIGHTENING_CLEARANCE_TOLERANCE < originalScore) {
                return false;
            }
        }
        return true;
    }

    private static SearchNode searchNode(int index, int cost, int x, int z, int targetX, int targetZ,
                                         int snapRadius, int clearanceScore) {
        int dx = Math.abs(targetX - x);
        int dz = Math.abs(targetZ - z);
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        int distance = min * DIAGONAL_COST + (max - min) * BLOCK_COST;
        int heuristic = Math.max(0, distance - snapRadius * BLOCK_COST);
        return new SearchNode(index, cost, heuristic, clearanceScore);
    }

    private record SearchNode(int index, int cost, int heuristic, int clearanceScore) {
        int estimatedTotalCost() { return cost + heuristic; }
    }
}
