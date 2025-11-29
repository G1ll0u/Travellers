package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class EntityTravellerBoat extends net.minecraft.entity.item.EntityBoat {
    // Movement threshold: if boat moves less than this squared distance per tick → “not moving”
    private static final double MIN_MOVE_SQ = 0.05 * 0.05; // ≈ 0.005 blocks/tick
    // Time to stall before killing boat (~1 second)
    private static final int STALL_LIMIT = 20;
    private Vec3d lastPos = Vec3d.ZERO;
    private int stallTicks = 0;

    public EntityTravellerBoat(World worldIn) {
        super(worldIn);
    }

    public EntityTravellerBoat(World worldIn, double x, double y, double z) {
        super(worldIn, x, y, z);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) return;

        // ---------------------------
        // 1. Auto-kill if empty
        // ---------------------------
        if (this.getPassengers().isEmpty()) {
            this.setDead();
            return;
        }

        // ---------------------------
        // 2. Stall detection (kill if nearly not moving for 1 sec)
        // ---------------------------
        Vec3d now = new Vec3d(this.posX, this.posY, this.posZ);

        if (lastPos == Vec3d.ZERO) {
            lastPos = now;
            stallTicks = 0;
            return;
        }

        double movedSq = now.squareDistanceTo(lastPos);

        if (movedSq < MIN_MOVE_SQ) {
            stallTicks++;
        } else {
            stallTicks = 0;
            lastPos = now;
        }

        if (stallTicks >= STALL_LIMIT) {
            // Kill the boat → traveller dismounts automatically
            this.setDead();
        }
    }

}
