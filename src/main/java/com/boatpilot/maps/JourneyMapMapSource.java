package com.boatpilot.maps;

import com.boatpilot.navigation.MapRaster;
import com.boatpilot.navigation.SurfaceType;
import com.boatpilot.navigation.WaterClassifier;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

/** JourneyMap image-tile adapter. Routing itself remains independent of JourneyMap. */
public final class JourneyMapMapSource implements MapSource, WaypointSource {
    private static final int MAX_ROUTE_DISTANCE = 4096;
    private static final int EDGE_MARGIN = 32;
    private static final int LOCAL_FALLBACK_RADIUS = 64;
    private static final int UNKNOWN_COLOR = 0xff777777;
    private final IClientAPI api;

    public JourneyMapMapSource(IClientAPI api) {
        this.api = api;
    }

    @Override
    public void findByName(String name, Consumer<Optional<BlockPos>> callback) {
        if (Minecraft.getInstance().level == null) {
            callback.accept(Optional.empty());
            return;
        }
        var matches = api.getAllWaypoints(Minecraft.getInstance().level.dimension()).stream()
            .filter(journeymap.api.v2.common.waypoint.Waypoint::isEnabled)
            .filter(waypoint -> waypoint.getName().equalsIgnoreCase(name))
            .toList();
        if (matches.size() == 1) callback.accept(Optional.of(matches.getFirst().getBlockPos()));
        else callback.accept(Optional.empty());
    }

    @Override
    public void request(BlockPos from, BlockPos to, Consumer<Optional<MapRaster>> callback) {
        int minX = Math.min(from.getX(), to.getX()) - EDGE_MARGIN;
        int minZ = Math.min(from.getZ(), to.getZ()) - EDGE_MARGIN;
        int maxX = Math.max(from.getX(), to.getX()) + EDGE_MARGIN;
        int maxZ = Math.max(from.getZ(), to.getZ()) + EDGE_MARGIN;
        int minChunkX = Math.floorDiv(minX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        int edgeX = (maxChunkX + 1) * 16;
        int edgeZ = (maxChunkZ + 1) * 16;
        minX = minChunkX * 16;
        minZ = minChunkZ * 16;
        int spanX = edgeX - minX;
        int spanZ = edgeZ - minZ;
        final int rasterMinX = minX;
        final int rasterMinZ = minZ;
        double routeDistance = Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
        int maxRasterSpan = MAX_ROUTE_DISTANCE + EDGE_MARGIN * 2 + 15;
        if (routeDistance > MAX_ROUTE_DISTANCE || spanX > maxRasterSpan || spanZ > maxRasterSpan) {
            callback.accept(Optional.empty());
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            callback.accept(Optional.empty());
            return;
        }
        ResourceKey<Level> dimension = minecraft.level.dimension();
        var xRanges = JourneyMapTileRanges.regions(minChunkX, maxChunkX);
        var zRanges = JourneyMapTileRanges.regions(minChunkZ, maxChunkZ);
        int tileCount = xRanges.size() * zRanges.size();
        int[] pixels = new int[spanX * spanZ];
        Arrays.fill(pixels, UNKNOWN_COLOR);
        int[] knownPixels = {0};
        AtomicInteger pendingTiles = new AtomicInteger(tileCount);
        Object resultLock = new Object();

        for (JourneyMapTileRanges.ChunkRange zRange : zRanges) {
            int tileChunkZ = zRange.first();
            int tileEndChunkZ = zRange.last();
            for (JourneyMapTileRanges.ChunkRange xRange : xRanges) {
                int tileChunkX = xRange.first();
                int tileEndChunkX = xRange.last();
                int copyStartChunkX = Math.max(minChunkX, tileChunkX);
                int copyStartChunkZ = Math.max(minChunkZ, tileChunkZ);
                int copyEndChunkX = Math.min(maxChunkX, tileEndChunkX);
                int copyEndChunkZ = Math.min(maxChunkZ, tileEndChunkZ);
                int tileMinX = copyStartChunkX * 16;
                int tileMinZ = copyStartChunkZ * 16;
                int tileSpanX = (copyEndChunkX - copyStartChunkX + 1) * 16;
                int tileSpanZ = (copyEndChunkZ - copyStartChunkZ + 1) * 16;
                int imageOffsetX = (copyStartChunkX - xRange.first()) * 16;
                int imageOffsetZ = (copyStartChunkZ - zRange.first()) * 16;
                AtomicBoolean tileCompleted = new AtomicBoolean();
                Consumer<com.mojang.blaze3d.platform.NativeImage> tileCallback = image -> {
                    if (!tileCompleted.compareAndSet(false, true)) return;
                    int[] tilePixels = rasterTile(image, minecraft.level, tileMinX, tileMinZ,
                        tileSpanX, tileSpanZ, imageOffsetX, imageOffsetZ, from, to);
                    int tileKnown = countKnown(tilePixels);
                    synchronized (resultLock) {
                        int offsetX = tileMinX - rasterMinX;
                        int offsetZ = tileMinZ - rasterMinZ;
                        for (int z = 0; z < tileSpanZ; z++) {
                            System.arraycopy(tilePixels, z * tileSpanX,
                                pixels, (offsetZ + z) * spanX + offsetX, tileSpanX);
                        }
                        knownPixels[0] += tileKnown;
                    }
                    if (pendingTiles.decrementAndGet() == 0) {
                        Optional<MapRaster> result;
                        synchronized (resultLock) {
                            result = knownPixels[0] == 0 ? Optional.empty()
                                : Optional.of(new MapRaster(rasterMinX, rasterMinZ, spanX, spanZ,
                                    spanX, spanZ, pixels));
                        }
                        minecraft.execute(() -> callback.accept(result));
                    }
                };
                try {
                    api.requestMapTile("boatpilot", dimension, Context.MapType.Day,
                        new ChunkPos(tileChunkX, tileChunkZ), new ChunkPos(tileEndChunkX, tileEndChunkZ),
                        null, 0, false, tileCallback);
                } catch (RuntimeException exception) {
                    tileCallback.accept(null);
                }
            }
        }
    }

    private static int[] rasterTile(com.mojang.blaze3d.platform.NativeImage image, Level level,
                                    int minX, int minZ, int spanX, int spanZ,
                                    int imageOffsetX, int imageOffsetZ, BlockPos from, BlockPos to) {
        int[] pixels = new int[spanX * spanZ];
        int imageWidth = image == null ? 0 : image.getWidth();
        int imageHeight = image == null ? 0 : image.getHeight();
        boolean sourceFits = imageWidth >= imageOffsetX + spanX && imageHeight >= imageOffsetZ + spanZ;
        for (int z = 0; z < spanZ; z++) {
            int sourceZ = imageOffsetZ + z;
            for (int x = 0; x < spanX; x++) {
                int blockX = minX + x;
                int blockZ = minZ + z;
                int sourceX = imageOffsetX + x;
                int pixel = !sourceFits ? UNKNOWN_COLOR : image.getPixel(sourceX, sourceZ);
                if (WaterClassifier.classify(pixel) == SurfaceType.UNKNOWN
                    && withinLocalFallbackRange(blockX, blockZ, from, to)) {
                    pixel = WaterClassifier.withLocalFallback(pixel, surfaceFromLoadedWorld(level, blockX, blockZ));
                }
                pixels[z * spanX + x] = pixel;
            }
        }
        return pixels;
    }

    private static int countKnown(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) {
            if (WaterClassifier.classify(pixel) != SurfaceType.UNKNOWN) count++;
        }
        return count;
    }

    private static SurfaceType surfaceFromLoadedWorld(Level level, int x, int z) {
        int sampleY = level.getMinY();
        if (!level.hasChunkAt(new BlockPos(x, sampleY, z))) return SurfaceType.UNKNOWN;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < level.getMinY()) return SurfaceType.UNKNOWN;
        BlockState state = level.getBlockState(new BlockPos(x, surfaceY, z));
        if (state.getFluidState().is(FluidTags.WATER)) return SurfaceType.WATER;
        return state.isAir() ? SurfaceType.UNKNOWN : SurfaceType.LAND;
    }

    private static boolean withinLocalFallbackRange(int x, int z, BlockPos from, BlockPos to) {
        return withinRadius(x, z, from) || withinRadius(x, z, to);
    }

    private static boolean withinRadius(int x, int z, BlockPos center) {
        return Math.abs(x - center.getX()) <= LOCAL_FALLBACK_RADIUS
            && Math.abs(z - center.getZ()) <= LOCAL_FALLBACK_RADIUS;
    }

}
