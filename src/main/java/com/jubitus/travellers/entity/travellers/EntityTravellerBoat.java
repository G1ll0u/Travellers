package com.jubitus.travellers.entity.travellers;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class EntityTravellerBoat extends net.minecraft.entity.item.EntityBoat {

    // Movement threshold: if boat moves less than this squared distance per tick → “not moving”
    private static final double MIN_MOVE_SQ = 0.05 * 0.05; // ≈ 0.005 blocks/tick
    // --- smarter despawn ---
    private static final int EMPTY_KILL_TICKS = 40;          // 2s empty grace (optional)
    private static final int BEACH_KILL_TICKS = 20 * 5;     // 10s beached
    private static final int IDLE_OCCUPIED_KILL_TICKS = 20 * 10; // 60s idle but occupied (safety)
    private static final int STUCK_KILL_TICKS = 20 * 15;     // 15s stuck while uncontrolled
    private static final double MIN_HORIZ_SPEED_SQ = 0.01 * 0.01; // tune (blocks/tick)^2
    private static final int SHORE_STUCK_EJECT_TICKS = 20 * 2;    // 2s
    private int trackingPlayers = 0;
    // Time to stall before killing boat (~1 second)
    private Vec3d lastPos = null;
    private int emptyTicks = 0;
    private int beachedTicks = 0;
    private int idleOccupiedTicks = 0;
    private int stuckUncontrolledTicks = 0;
    private boolean pendingKill = false;
    private boolean killing = false;
    private int shoreStuckTicks = 0;

    public EntityTravellerBoat(World worldIn) {
        super(worldIn);
    }

    public EntityTravellerBoat(World worldIn, double x, double y, double z) {
        super(worldIn, x, y, z);
    }

    public boolean isTrackedByAnyPlayer() {
        return trackingPlayers > 0;
    }

    public void incTracking() {
        trackingPlayers++;
    }

    public void decTracking() {
        trackingPlayers = Math.max(0, trackingPlayers - 1);
    }

    @Override
    public void setDead() {
        if (killing) {
            super.setDead();
            return;
        }
        killing = true;

        if (!world.isRemote && !this.getPassengers().isEmpty()) {
            this.removePassengers();
        }

        super.setDead();
    }

    // Not strictly required, but sometimes helps keep activation behavior sane.
    @Override
    public boolean isInRangeToRenderDist(double distance) {
        return true; // only affects render checks; server tick is still activation-range controlled
    }

    @Override
    public boolean writeToNBTOptional(NBTTagCompound compound) {
        // Never save traveller boats; prevents cross-chunk Passenger/Riding NBT recursion
        return false;
    }

    public void killNow() {
        if (this.isDead || killing) return;

        killing = true;

        if (!world.isRemote) {
            // Explicitly dismount passengers (stronger than just removePassengers())
            for (Entity p : new java.util.ArrayList<>(this.getPassengers())) {
                p.dismountRidingEntity();
            }
            this.removePassengers(); // belt + suspenders
        }

        super.setDead();
    }

    @Override
    public boolean canBePushed() {
        return true;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) return;
// Safer than chunkCoord checks: only kill if passenger is truly desynced
        if (!this.getPassengers().isEmpty()) {
            for (Entity p : this.getPassengers()) {

                // If passenger somehow isn't riding this boat anymore -> clean up
                if (p.getRidingEntity() != this) {
                    ejectAndKill();
                    return;
                }

                // If passenger is wildly far away (real desync), then kill
                if (p.getDistanceSq(this) > 16.0) { // 4 blocks radius; tune if you want
                    ejectAndKill();
                    return;
                }

                // Optional extra safety: if passenger chunk isn't loaded, bail
                BlockPos pp = new BlockPos(p.posX, p.posY, p.posZ);
                if (!world.isBlockLoaded(pp)) {
                    return; // just skip; don't kill due to unknown
                }
            }
        }


        // If we ejected last tick, finish the kill once empty
        if (pendingKill && this.getPassengers().isEmpty()) {
            pendingKill = false;
            requestKill();
            return;
        }

        // ---------------------------
        // 0) movement sample
        // ---------------------------
        Vec3d now = new Vec3d(this.posX, this.posY, this.posZ);
        boolean moved = true;

        if (lastPos == null) {
            lastPos = now;
        } else {
            double movedSq = now.squareDistanceTo(lastPos);
            moved = movedSq >= MIN_MOVE_SQ;
            lastPos = now; // <-- always update
        }


        boolean empty = this.getPassengers().isEmpty();
        boolean onWater = isOnWaterSurface();
        boolean driverPresent = hasDriver();

        // --- shore-stuck handling (boat still "on water" but pinned on shoreline) ---


        boolean movingHoriz = hasMeaningfulHorizontalSpeed();
        boolean pinned = this.collidedHorizontally
                || (!movingHoriz && isTouchingSolidLand());

// If occupied and pinned and not moving: eject + kill after short delay
        if (!empty && pinned && !movingHoriz) {
            shoreStuckTicks++;
            if (shoreStuckTicks >= SHORE_STUCK_EJECT_TICKS) {
                ejectAndKill();
                return;
            }
        } else {
            shoreStuckTicks = 0;
        }

        // ---------------------------
        // 1) Empty boats: kill (but give tiny grace so mount/dismount ticks don't instantly delete)
        // ---------------------------
        if (empty) {
            emptyTicks++;
            if (emptyTicks >= EMPTY_KILL_TICKS) {
                requestKill();
            }
            return;
        } else {
            emptyTicks = 0;
        }

        // ---------------------------
        // 2) Beached boats: if occupied but not on water for long -> kill
        // (this cleans up shore boats even if they get abandoned)
        // ---------------------------
        if (!onWater) {
            beachedTicks++;
            if (beachedTicks >= BEACH_KILL_TICKS) {
                requestKill();
            }
            // while beached, no need to count other timers aggressively
            return;
        } else {
            beachedTicks = 0;
        }

        // ---------------------------
        // 3) If occupied but nobody is steering it:
        // - if it's not moving for a while, it's probably abandoned -> kill eventually
        // ---------------------------
        if (!driverPresent) {
            boolean progressing = hasMeaningfulHorizontalSpeed() && !pinned;

            if (!progressing) stuckUncontrolledTicks++;
            else stuckUncontrolledTicks = 0;

            if (stuckUncontrolledTicks >= STUCK_KILL_TICKS) {
                ejectAndKill();
            }
            return;
        } else {
            stuckUncontrolledTicks = 0;
        }

        // ---------------------------
        // 4) Safety: occupied + driver present but idle forever -> kill much later
        // (prevents permanent boats if something breaks)
        // ---------------------------
        if (!moved) {
            idleOccupiedTicks++;
            if (idleOccupiedTicks >= IDLE_OCCUPIED_KILL_TICKS) {
                requestKill();
            }
        } else {
            idleOccupiedTicks = 0;
        }
    }

    private void ejectAndKill() {
        this.removePassengers();
        pendingKill = true;
    }

    public void requestKill() {
        if (this.isDead || killing) return;

        // Always clear passengers immediately
        if (!this.getPassengers().isEmpty()) {
            for (Entity p : new java.util.ArrayList<>(this.getPassengers())) {
                p.dismountRidingEntity();
            }
            this.removePassengers();
        }

        killing = true;
        super.setDead();
    }

    private boolean isOnWaterSurface() {
        BlockPos pos = new BlockPos(
                net.minecraft.util.math.MathHelper.floor(this.posX),
                net.minecraft.util.math.MathHelper.floor(this.posY - 0.2D),
                net.minecraft.util.math.MathHelper.floor(this.posZ)
        );

        if (!world.isBlockLoaded(pos)) return true; // don't despawn on unloaded chunks logic here

        IBlockState feet = world.getBlockState(pos);
        IBlockState below = world.getBlockState(pos.down());

        Block b1 = feet.getBlock();
        Block b2 = below.getBlock();

        return (b1 == Blocks.WATER || b1 == Blocks.FLOWING_WATER ||
                b2 == Blocks.WATER || b2 == Blocks.FLOWING_WATER);
    }

    private boolean hasDriver() {
        // vanilla boat: controlling passenger is "driver"
        return this.getControllingPassenger() != null;
    }

    private boolean hasMeaningfulHorizontalSpeed() {
        double v2 = this.motionX * this.motionX + this.motionZ * this.motionZ;
        return v2 >= MIN_HORIZ_SPEED_SQ;
    }

    private boolean isTouchingSolidLand() {
        AxisAlignedBB bb = this.getEntityBoundingBox().grow(0.05D);
        int minX = net.minecraft.util.math.MathHelper.floor(bb.minX);
        int maxX = net.minecraft.util.math.MathHelper.floor(bb.maxX);
        int minY = net.minecraft.util.math.MathHelper.floor(bb.minY);
        int maxY = net.minecraft.util.math.MathHelper.floor(bb.maxY);
        int minZ = net.minecraft.util.math.MathHelper.floor(bb.minZ);
        int maxZ = net.minecraft.util.math.MathHelper.floor(bb.maxZ);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    p.setPos(x, y, z);
                    if (!world.isBlockLoaded(p)) continue;

                    IBlockState s = world.getBlockState(p);
                    if (!s.getMaterial().isLiquid() && s.getMaterial().isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


}
