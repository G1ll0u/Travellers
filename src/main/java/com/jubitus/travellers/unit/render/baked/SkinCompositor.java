package com.jubitus.travellers.unit.render.baked;

/**
 * Pixel operations that flatten a biped's layers into one 64x32-layout texture.
 * Pure ARGB arrays, no GL, so it stays testable.
 */
public final class SkinCompositor {

    // Biped box regions in 64x32 texture units: {u0, v0, u1, v1}.
    static final int[] HEAD = {0, 0, 32, 16};
    static final int[] HEADWEAR = {32, 0, 64, 16};
    static final int[] LEG = {0, 16, 16, 32};
    static final int[] BODY = {16, 16, 40, 32};
    static final int[] ARM = {40, 16, 56, 32};

    // Parts each armour slot shows, as in LayerBipedArmor.setModelSlotVisible.
    public static final int[][] SLOT_HEAD = {HEAD, HEADWEAR};
    public static final int[][] SLOT_CHEST = {BODY, ARM};
    public static final int[][] SLOT_LEGS = {BODY, LEG};
    public static final int[][] SLOT_FEET = {LEG};

    // Vanilla's default alpha test (GREATER 0.1) used by unblended armour passes.
    private static final int ALPHA_TEST = 26;
    private static final int MAX_SIZE = 256;

    private SkinCompositor() {
    }

    public static final class Image {
        public final int width;
        public final int height;
        public final int[] argb;

        public Image(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    /** Output width: the sharpest source wins, kept a multiple of 64 and capped. */
    public static int outputWidth(int maxSourceWidth) {
        int w = Math.max(64, Math.min(MAX_SIZE, maxSourceWidth));
        return w - (w % 64);
    }

    /** Nearest-neighbour stretch: the model samples normalized UVs, so any source size maps onto the 64x32 layout. */
    public static int[] resize(Image src, int width, int height) {
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            int sy = y * src.height / height;
            for (int x = 0; x < width; x++) {
                out[y * width + x] = src.argb[sy * src.width + x * src.width / width];
            }
        }
        return out;
    }

    /** Alpha-blended pass (clothes, owner mark), optionally tinted. */
    public static void over(int[] dst, int[] src, int tintRgb) {
        for (int i = 0; i < dst.length; i++) {
            int s = tint(src[i], tintRgb);
            int sa = s >>> 24;
            if (sa == 0) continue;
            int d = dst[i];
            int da = d >>> 24;
            int outA = sa + da * (255 - sa) / 255;
            if (outA == 0) continue;
            int r = blend(s >> 16 & 255, sa, d >> 16 & 255, da, outA);
            int g = blend(s >> 8 & 255, sa, d >> 8 & 255, da, outA);
            int b = blend(s & 255, sa, d & 255, da, outA);
            dst[i] = outA << 24 | r << 16 | g << 8 | b;
        }
    }

    /** Unblended alpha-tested pass (armour) limited to the parts the slot shows; kept texels become opaque. */
    public static void cutout(int[] dst, int[] src, int tintRgb, int width, int height, int[][] regions) {
        for (int[] region : regions) {
            int x0 = region[0] * width / 64, x1 = region[2] * width / 64;
            int y0 = region[1] * height / 32, y1 = region[3] * height / 32;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int i = y * width + x;
                    int s = src[i];
                    if ((s >>> 24) > ALPHA_TEST) {
                        dst[i] = 0xFF000000 | (tint(s, tintRgb) & 0xFFFFFF);
                    }
                }
            }
        }
    }

    private static int tint(int argb, int rgb) {
        if (rgb == 0xFFFFFF) return argb;
        int r = (argb >> 16 & 255) * (rgb >> 16 & 255) / 255;
        int g = (argb >> 8 & 255) * (rgb >> 8 & 255) / 255;
        int b = (argb & 255) * (rgb & 255) / 255;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int blend(int sc, int sa, int dc, int da, int outA) {
        return (sc * sa + dc * da * (255 - sa) / 255) / outA;
    }
}
