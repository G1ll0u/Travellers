package com.jubitus.travellers.utils.world.pathing;

public final class GapJumpResult {
    public enum Type { NONE, JUMPABLE, UNJUMPABLE }

    public static final GapJumpResult NONE = new GapJumpResult(Type.NONE, 0, 0, 0, 0);
    public static final GapJumpResult UNJUMPABLE = new GapJumpResult(Type.UNJUMPABLE, 0, 0, 0, 0);

    public final Type type;
    public final double landX;
    public final double landY;
    public final double landZ;
    public final int gapWidth;

    private GapJumpResult(Type type, double landX, double landY, double landZ, int gapWidth) {
        this.type = type;
        this.landX = landX;
        this.landY = landY;
        this.landZ = landZ;
        this.gapWidth = gapWidth;
    }

    public static GapJumpResult jumpable(double landX, double landY, double landZ, int gapWidth) {
        return new GapJumpResult(Type.JUMPABLE, landX, landY, landZ, gapWidth);
    }
}
