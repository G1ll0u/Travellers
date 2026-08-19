package com.jubitus.travellers.server.map.pyramid;

/** Identifies one tile anywhere in the pyramid: dimension + level + tile coordinate. */
public final class PyramidTileRef {
    public final int dimension;
    public final int level;
    public final int tileX;
    public final int tileZ;

    public PyramidTileRef(int dimension, int level, int tileX, int tileZ) {
        this.dimension = dimension;
        this.level = level;
        this.tileX = tileX;
        this.tileZ = tileZ;
    }

    public PyramidTileRef parent() {
        return new PyramidTileRef(dimension, level + 1, Math.floorDiv(tileX, 2), Math.floorDiv(tileZ, 2));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PyramidTileRef)) return false;
        PyramidTileRef r = (PyramidTileRef) o;
        return dimension == r.dimension && level == r.level && tileX == r.tileX && tileZ == r.tileZ;
    }

    @Override
    public int hashCode() {
        int h = dimension;
        h = h * 31 + level;
        h = h * 31 + tileX;
        h = h * 31 + tileZ;
        return h;
    }
}
