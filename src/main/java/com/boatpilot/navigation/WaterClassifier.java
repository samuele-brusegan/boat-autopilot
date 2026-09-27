package com.boatpilot.navigation;

/** Conservative classifier for JourneyMap's default day-map palette. */
public final class WaterClassifier {
    private static final int WATER_COLOR = 0xff4c8fd0;
    private static final int LAND_COLOR = 0xff5c9847;

    private WaterClassifier() {
    }

    public static SurfaceType classify(int rgb) {
        int red = (rgb >>> 16) & 0xff;
        int green = (rgb >>> 8) & 0xff;
        int blue = rgb & 0xff;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        float delta = max - min;
        float saturation = max == 0 ? 0 : delta / max;
        float brightness = max / 255.0f;
        float hue;
        if (delta == 0) hue = 0;
        else if (max == red) hue = ((green - blue) / delta + (green < blue ? 6 : 0)) / 6.0f;
        else if (max == green) hue = ((blue - red) / delta + 2) / 6.0f;
        else hue = ((red - green) / delta + 4) / 6.0f;

        // Deep/shallow water in JourneyMap's standard day palette is blue-cyan.
        if (hue >= 0.48f && hue <= 0.69f && saturation >= 0.16f && brightness >= 0.18f) {
            return SurfaceType.WATER;
        }

        // The normal map uses green, tan, and brown for exposed terrain.
        if (saturation >= 0.16f && hue >= 0.08f && hue < 0.43f && brightness >= 0.16f) {
            return SurfaceType.LAND;
        }

        return SurfaceType.UNKNOWN;
    }

    /** Uses locally known terrain only where the map image itself is unclassified. */
    public static int withLocalFallback(int mapPixel, SurfaceType localSurface) {
        if (classify(mapPixel) != SurfaceType.UNKNOWN || localSurface == SurfaceType.UNKNOWN) return mapPixel;
        return localSurface == SurfaceType.WATER ? WATER_COLOR : LAND_COLOR;
    }

    public static int colorFor(SurfaceType surface, int unknownFallback) {
        return switch (surface) {
            case WATER -> WATER_COLOR;
            case LAND -> LAND_COLOR;
            case UNKNOWN -> unknownFallback;
        };
    }
}
