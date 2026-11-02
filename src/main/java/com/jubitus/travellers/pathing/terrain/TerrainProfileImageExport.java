package com.jubitus.travellers.pathing.terrain;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Debug PNG export of the terrain profile grid, so a scanned region can be eyeballed against the
 * real world. Server thread, admin command only ({@code /travellersdebug terrain export}).
 *
 * <p>One pixel-block per grid cell (16 blocks). North is up, +X is right. Cells are rendered from
 * whatever is stored - {@code EMPTY} cells show near-black, {@code BIOME_EST} cells are dimmed,
 * {@code REAL_SCAN} cells full brightness - so the image doubles as a coverage map. Pass
 * {@code fill} to first fold in biome estimates for the whole box (small radii only).
 */
public final class TerrainProfileImageExport {

    public enum Mode {HEIGHT, WATER, AMP, SOURCE, BIOME}

    private static final int MAX_RADIUS = 8192;
    private static final int FILL_MAX_RADIUS = 4096;
    private static final int TARGET_IMAGE_PX = 1024;

    private TerrainProfileImageExport() {
    }

    /** Parses a mode token; {@code "all"} -> every mode. */
    public static List<Mode> parseModes(String token) {
        List<Mode> out = new ArrayList<>();
        if (token == null || token.equalsIgnoreCase("all")) {
            for (Mode m : Mode.values()) out.add(m);
            return out;
        }
        for (Mode m : Mode.values()) {
            if (m.name().equalsIgnoreCase(token)) {
                out.add(m);
                return out;
            }
        }
        return out; // empty -> caller reports "unknown mode"
    }

    public static final class Result {
        public final List<File> files = new ArrayList<>();
        public final int cellsWide;
        public final int cellsHigh;
        public final int cellPx;
        public final int minHeight;
        public final int maxHeight;
        public final int realScan;
        public final int biomeEst;
        public final int empty;
        public final boolean filled;

        Result(int cellsWide, int cellsHigh, int cellPx, int minHeight, int maxHeight,
               int realScan, int biomeEst, int empty, boolean filled) {
            this.cellsWide = cellsWide;
            this.cellsHigh = cellsHigh;
            this.cellPx = cellPx;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.realScan = realScan;
            this.biomeEst = biomeEst;
            this.empty = empty;
            this.filled = filled;
        }
    }

    public static Result export(MinecraftServer server, WorldServer world, TerrainProfileGrid grid,
                                int centerX, int centerZ, int radiusBlocks, List<Mode> modes,
                                boolean fill) throws IOException {
        int radius = Math.max(128, Math.min(MAX_RADIUS, radiusBlocks));

        int cMinX = TerrainProfile.cellCoord(centerX - radius);
        int cMaxX = TerrainProfile.cellCoord(centerX + radius);
        int cMinZ = TerrainProfile.cellCoord(centerZ - radius);
        int cMaxZ = TerrainProfile.cellCoord(centerZ + radius);
        int wCells = cMaxX - cMinX + 1;
        int hCells = cMaxZ - cMinZ + 1;

        // Bring every region overlapping the box into memory (bounded: a few regions at MAX_RADIUS).
        for (int rz = TerrainProfile.regionCoord(cMinZ); rz <= TerrainProfile.regionCoord(cMaxZ); rz++) {
            for (int rx = TerrainProfile.regionCoord(cMinX); rx <= TerrainProfile.regionCoord(cMaxX); rx++) {
                grid.loadRegionBlocking(rx * TerrainProfile.REGION_CELLS, rz * TerrainProfile.REGION_CELLS);
            }
        }

        boolean filled = false;
        if (fill && radius <= FILL_MAX_RADIUS) {
            grid.ensureEstimated(world, centerX - radius, centerZ - radius, centerX + radius, centerZ + radius);
            filled = true;
        }

        // First pass: height range + coverage counts.
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        int nReal = 0, nEst = 0, nEmpty = 0;
        for (int cz = cMinZ; cz <= cMaxZ; cz++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                int wx = cx * TerrainProfile.CELL_SIZE + 8;
                int wz = cz * TerrainProfile.CELL_SIZE + 8;
                byte src = grid.getSource(wx, wz);
                if (src == TerrainProfile.SOURCE_EMPTY) {
                    nEmpty++;
                    continue;
                }
                if (src == TerrainProfile.SOURCE_REAL_SCAN) nReal++;
                else nEst++;
                int h = grid.getHeight(wx, wz);
                if (h == Short.MIN_VALUE) continue;
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
            }
        }
        if (minH > maxH) {
            minH = 40;
            maxH = 140;
        }
        int span = Math.max(1, maxH - minH);

        int cellPx = Math.max(1, Math.min(10, Math.round((float) TARGET_IMAGE_PX / Math.max(wCells, hCells))));

        int playerCellX = TerrainProfile.cellCoord(centerX);
        int playerCellZ = TerrainProfile.cellCoord(centerZ);

        File outDir = com.jubitus.travellers.debug.DebugFiles.dir(com.jubitus.travellers.debug.DebugFiles.TERRAIN);
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        String stamp = new SimpleDateFormat("HHmmss").format(new Date());

        Result result = new Result(wCells, hCells, cellPx, minH, maxH, nReal, nEst, nEmpty, filled);

        for (Mode mode : modes) {
            BufferedImage img = new BufferedImage(wCells * cellPx, hCells * cellPx, BufferedImage.TYPE_INT_RGB);
            for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                for (int cx = cMinX; cx <= cMaxX; cx++) {
                    int wx = cx * TerrainProfile.CELL_SIZE + 8;
                    int wz = cz * TerrainProfile.CELL_SIZE + 8;
                    int rgb = colorFor(grid, mode, wx, wz, minH, span);
                    fillCell(img, (cx - cMinX) * cellPx, (cz - cMinZ) * cellPx, cellPx, rgb);
                }
            }
            drawCross(img, (playerCellX - cMinX) * cellPx + cellPx / 2,
                    (playerCellZ - cMinZ) * cellPx + cellPx / 2, 0xFFFF3030);

            File f = new File(outDir, String.format("terrain_dim%d_%s_c%d_%d_r%d_%s.png",
                    world.provider.getDimension(), mode.name().toLowerCase(), centerX, centerZ, radius, stamp));
            ImageIO.write(img, "PNG", f);
            result.files.add(f);
        }
        return result;
    }

    // ------------------------------------------------------------------

    /**
     * Samples a rectangular block of grid cells into a row-major ARGB array (row 0 = north). Used by
     * the debug worldmap overlay. Block-loads the covered regions; renders whatever is stored
     * ({@code EMPTY} cells show near-black, {@code BIOME_EST} dimmed).
     */
    public static int[] sampleArea(TerrainProfileGrid grid, Mode mode,
                                   int minCellX, int minCellZ, int wCells, int hCells) {
        for (int rz = TerrainProfile.regionCoord(minCellZ);
             rz <= TerrainProfile.regionCoord(minCellZ + hCells - 1); rz++) {
            for (int rx = TerrainProfile.regionCoord(minCellX);
                 rx <= TerrainProfile.regionCoord(minCellX + wCells - 1); rx++) {
                grid.loadRegionBlocking(rx * TerrainProfile.REGION_CELLS, rz * TerrainProfile.REGION_CELLS);
            }
        }
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (int cz = 0; cz < hCells; cz++) {
            for (int cx = 0; cx < wCells; cx++) {
                int wx = (minCellX + cx) * TerrainProfile.CELL_SIZE + 8;
                int wz = (minCellZ + cz) * TerrainProfile.CELL_SIZE + 8;
                if (grid.getSource(wx, wz) == TerrainProfile.SOURCE_EMPTY) continue;
                int h = grid.getHeight(wx, wz);
                if (h == Short.MIN_VALUE) continue;
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
            }
        }
        if (minH > maxH) {
            minH = 40;
            maxH = 140;
        }
        int span = Math.max(1, maxH - minH);

        int[] out = new int[wCells * hCells];
        for (int cz = 0; cz < hCells; cz++) {
            for (int cx = 0; cx < wCells; cx++) {
                int wx = (minCellX + cx) * TerrainProfile.CELL_SIZE + 8;
                int wz = (minCellZ + cz) * TerrainProfile.CELL_SIZE + 8;
                out[cz * wCells + cx] = colorFor(grid, mode, wx, wz, minH, span);
            }
        }
        return out;
    }

    static int colorFor(TerrainProfileGrid grid, Mode mode, int wx, int wz, int minH, int span) {
        byte src = grid.getSource(wx, wz);
        if (src == TerrainProfile.SOURCE_EMPTY) return 0xFF0E1216;

        boolean est = src == TerrainProfile.SOURCE_BIOME_EST;
        int rgb;
        switch (mode) {
            case WATER: {
                int w = grid.getWater(wx, wz);
                rgb = lerp(0xFF35502F, 0xFF1E64FF, w / 255.0);
                break;
            }
            case AMP: {
                int a = Math.min(255, grid.getAmplitude(wx, wz));
                float hue = 0.66f - 0.66f * (a / 255.0f); // blue (flat) -> red (rough)
                rgb = 0xFF000000 | (Color.HSBtoRGB(hue, 0.85f, 0.25f + 0.75f * (a / 255.0f)) & 0xFFFFFF);
                break;
            }
            case SOURCE:
                return est ? 0xFFC8A000 : 0xFF2CB82C;
            case BIOME: {
                short bi = grid.getBiomeIx(wx, wz);
                if (bi < 0) return 0xFF555555;
                rgb = 0xFF000000 | (Color.HSBtoRGB((bi * 0.1379f) % 1.0f, 0.55f, 0.92f) & 0xFFFFFF);
                break;
            }
            case HEIGHT:
            default: {
                int h = grid.getHeight(wx, wz);
                double t = h == Short.MIN_VALUE ? 0.0 : Math.max(0.0, Math.min(1.0, (h - minH) / (double) span));
                int v = (int) Math.round(40 + t * 215);
                int w = grid.getWater(wx, wz);
                if (w >= 128) {
                    rgb = 0xFF000000 | (clamp(v / 5) << 16) | (clamp(v / 2) << 8) | clamp(90 + v / 2);
                } else {
                    rgb = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                break;
            }
        }
        if (est) rgb = dim(rgb, 0.45);
        return rgb;
    }

    private static int lerp(int a, int b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int bl = (int) Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int dim(int rgb, double f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static void fillCell(BufferedImage img, int px, int pz, int size, int rgb) {
        int w = img.getWidth(), h = img.getHeight();
        for (int dz = 0; dz < size; dz++) {
            int y = pz + dz;
            if (y < 0 || y >= h) continue;
            for (int dx = 0; dx < size; dx++) {
                int x = px + dx;
                if (x < 0 || x >= w) continue;
                img.setRGB(x, y, rgb);
            }
        }
    }

    private static void drawCross(BufferedImage img, int cx, int cy, int rgb) {
        int w = img.getWidth(), h = img.getHeight();
        for (int d = -4; d <= 4; d++) {
            if (cy >= 0 && cy < h && cx + d >= 0 && cx + d < w) img.setRGB(cx + d, cy, rgb);
            if (cx >= 0 && cx < w && cy + d >= 0 && cy + d < h) img.setRGB(cx, cy + d, rgb);
        }
    }
}
