package com.boatpilot.navigation;

import java.util.Objects;

/** A north-up raster with a block-coordinate rectangle represented by its edges. */
public final class MapRaster {
    private static final SurfaceType[] SURFACE_TYPES = SurfaceType.values();
    private final int minBlockX;
    private final int minBlockZ;
    private final int blockSpanX;
    private final int blockSpanZ;
    private final int width;
    private final int height;
    private final int[] pixels;
    private final byte[] surfaces;

    public MapRaster(int minBlockX, int minBlockZ, int blockSpanX, int blockSpanZ,
                     int width, int height, int[] pixels) {
        if (blockSpanX <= 0 || blockSpanZ <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Raster dimensions must be positive");
        }
        if (Objects.requireNonNull(pixels).length != width * height) {
            throw new IllegalArgumentException("Pixel count does not match raster dimensions");
        }
        this.minBlockX = minBlockX;
        this.minBlockZ = minBlockZ;
        this.blockSpanX = blockSpanX;
        this.blockSpanZ = blockSpanZ;
        this.width = width;
        this.height = height;
        this.pixels = pixels.clone();
        this.surfaces = new byte[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            surfaces[index] = (byte) WaterClassifier.classify(pixels[index]).ordinal();
        }
    }

    public int width() { return width; }
    public int height() { return height; }
    public int minBlockX() { return minBlockX; }
    public int minBlockZ() { return minBlockZ; }
    public int blockSpanX() { return blockSpanX; }
    public int blockSpanZ() { return blockSpanZ; }

    public int blockX(int pixelX) {
        return minBlockX + Math.min(blockSpanX - 1, (int) ((pixelX + 0.5) * blockSpanX / width));
    }

    public int blockZ(int pixelZ) {
        return minBlockZ + Math.min(blockSpanZ - 1, (int) ((pixelZ + 0.5) * blockSpanZ / height));
    }

    public int pixelX(int blockX) {
        if (blockX < minBlockX || blockX >= minBlockX + blockSpanX) return -1;
        return Math.min(width - 1, (int) ((blockX - minBlockX) * (double) width / blockSpanX));
    }

    public int pixelZ(int blockZ) {
        if (blockZ < minBlockZ || blockZ >= minBlockZ + blockSpanZ) return -1;
        return Math.min(height - 1, (int) ((blockZ - minBlockZ) * (double) height / blockSpanZ));
    }

    public int pixel(int x, int z) {
        return pixels[z * width + x];
    }

    public SurfaceType surface(int x, int z) {
        if (x < 0 || z < 0 || x >= width || z >= height) return SurfaceType.UNKNOWN;
        return SURFACE_TYPES[surfaces[z * width + x]];
    }
}
