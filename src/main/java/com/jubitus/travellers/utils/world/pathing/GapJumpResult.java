package com.jubitus.travellers.utils.world.pathing;

public final class GapJumpResult {
    public enum Type { NONE, JUMPABLE, UNJUMPABLE }

    public static final GapJumpResult NONE = new GapJumpResult(Type.NONE, 0, 0, 0, 0, 0, 0, 0);
    public static final GapJumpResult UNJUMPABLE = new GapJumpResult(Type.UNJUMPABLE, 0, 0, 0, 0, 0, 0, 0);

    public final Type type;
    public final double landX;
    public final double landY;
    public final double landZ;
    public final int gapWidth;

    // Integer landing "feet" cell (block position an entity would occupy after landing).
    // Kept separate from the continuous landX/Y/Z above so pathfinding code can build an exact
    // PathPoint without re-deriving it via floor(double) — that round-trip breaks on partial-height
    // landing blocks (slabs etc.), where floor(landY) does not equal the feet cell.
    public final int landCellX;
    public final int landCellY;
    public final int landCellZ;

    private GapJumpResult(Type type, double landX, double landY, double landZ, int gapWidth,
                           int landCellX, int landCellY, int landCellZ) {
        this.type = type;
        this.landX = landX;
        this.landY = landY;
        this.landZ = landZ;
        this.gapWidth = gapWidth;
        this.landCellX = landCellX;
        this.landCellY = landCellY;
        this.landCellZ = landCellZ;
    }

    public static GapJumpResult jumpable(double landX, double landY, double landZ, int gapWidth,
                                          int landCellX, int landCellY, int landCellZ) {
        return new GapJumpResult(Type.JUMPABLE, landX, landY, landZ, gapWidth, landCellX, landCellY, landCellZ);
    }
}
