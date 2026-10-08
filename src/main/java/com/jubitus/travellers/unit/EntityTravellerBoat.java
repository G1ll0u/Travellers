package com.jubitus.travellers.unit;

import com.jubitus.travellers.ground.BlockRules;
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

    private static final double MIN_MOVE_SQ = 0.05D * 0.05D;
    private static final double MIN_HORIZ_SPEED_SQ = 0.01D * 0.01D;

    private static final int EMPTY_KILL_TICKS = 40;
    private static final int EMPTY_BEACHED_KILL_TICKS = 20 * 5;
    private static final int OCCUPIED_UNCONTROLLED_KILL_TICKS = 20 * 15;
    private static final int OCCUPIED_HARD_STUCK_KILL_TICKS = 20 * 20;

    private int trackingPlayers = 0;

    private Vec3d lastPos = null;
    private int emptyTicks = 0;
    private int beachedTicks = 0;
    private int stuckUncontrolledTicks = 0;
    private int hardStuckControlledTicks = 0;

    private boolean pendingKill = false;
    private boolean killing = false;

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
    public boolean isInRangeToRenderDist(double distance) {
        return true;
    }

    @Override
    public boolean writeToNBTOptional(NBTTagCompound compound) {
        return false;
    }

    @Override
    public void setDead() {
        if (killing) {
            super.setDead();
            return;
        }

        killing = true;

        if (!world.isRemote && !this.getPassengers().isEmpty()) {
            restoreSaddles();
            this.removePassengers();
        }

        super.setDead();
    }

    // Whoever removes the boat, every traveller aboard gets its saddle back (boarding switched it to LED).
    private void restoreSaddles() {
        for (Entity p : this.getPassengers()) {
            if (p instanceof com.jubitus.travellers.unit.EntityTraveller) {
                com.jubitus.travellers.mount.TravellerMountSupport.setMountMode(
                        (com.jubitus.travellers.unit.EntityTraveller) p,
                        com.jubitus.travellers.mount.MountMode.MOUNTED);
            }
        }
    }

    public void killNow() {
        if (this.isDead || killing) return;

        killing = true;

        if (!world.isRemote) {
            restoreSaddles();
            for (Entity p : new java.util.ArrayList<>(this.getPassengers())) {
                p.dismountRidingEntity();
            }
            this.removePassengers();
        }

        super.setDead();
    }

    public void requestKill() {
        if (this.isDead || killing) return;

        if (!this.getPassengers().isEmpty()) {
            if (!world.isRemote) restoreSaddles();
            for (Entity p : new java.util.ArrayList<>(this.getPassengers())) {
                p.dismountRidingEntity();
            }
            this.removePassengers();
        }

        killing = true;
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

        if (!validatePassengers()) {
            return;
        }

        if (pendingKill && this.getPassengers().isEmpty()) {
            pendingKill = false;
            requestKill();
            return;
        }

        Vec3d now = new Vec3d(this.posX, this.posY, this.posZ);
        boolean moved = true;

        if (lastPos == null) {
            lastPos = now;
        } else {
            double movedSq = now.squareDistanceTo(lastPos);
            moved = movedSq >= MIN_MOVE_SQ;
            lastPos = now;
        }

        boolean empty = this.getPassengers().isEmpty();
        boolean onWater = isOnWaterSurface();
        boolean driverPresent = hasDriver();
        boolean movingHoriz = hasMeaningfulHorizontalSpeed();
        boolean pinned = this.collidedHorizontally || (!movingHoriz && isTouchingSolidLand());

        if (empty) {
            hardStuckControlledTicks = 0;
            stuckUncontrolledTicks = 0;

            emptyTicks++;
            if (!onWater) {
                beachedTicks++;
            } else {
                beachedTicks = 0;
            }

            if (emptyTicks >= EMPTY_KILL_TICKS || beachedTicks >= EMPTY_BEACHED_KILL_TICKS) {
                requestKill();
            }
            return;
        }

        emptyTicks = 0;

        if (!driverPresent) {
            hardStuckControlledTicks = 0;

            if (!movingHoriz && (pinned || !onWater)) {
                stuckUncontrolledTicks++;
            } else {
                stuckUncontrolledTicks = 0;
            }

            if (!onWater) {
                beachedTicks++;
            } else {
                beachedTicks = 0;
            }

            if (stuckUncontrolledTicks >= OCCUPIED_UNCONTROLLED_KILL_TICKS
                    || beachedTicks >= OCCUPIED_UNCONTROLLED_KILL_TICKS) {
                ejectAndKill();
            }
            return;
        }

        stuckUncontrolledTicks = 0;

        if (!onWater) {
            if (!moved || pinned) {
                beachedTicks++;
            } else if (beachedTicks > 0) {
                beachedTicks--;
            }
        } else {
            beachedTicks = 0;
        }

        if (pinned && !movingHoriz) {
            hardStuckControlledTicks++;
        } else if (hardStuckControlledTicks > 0) {
            hardStuckControlledTicks--;
        }

        if (hardStuckControlledTicks >= OCCUPIED_HARD_STUCK_KILL_TICKS) {
            ejectAndKill();
        }
    }

    private boolean validatePassengers() {
        if (this.getPassengers().isEmpty()) {
            return true;
        }

        for (Entity p : new java.util.ArrayList<>(this.getPassengers())) {
            if (p == null || p.isDead) {
                ejectAndKill();
                return false;
            }

            if (p.getRidingEntity() != this) {
                ejectAndKill();
                return false;
            }

            if (p.getDistanceSq(this) > 36.0D) {
                ejectAndKill();
                return false;
            }

            BlockPos pp = new BlockPos(p.posX, p.posY, p.posZ);
            if (!world.isBlockLoaded(pp)) {
                return true;
            }
        }

        return true;
    }

    private void ejectAndKill() {
        this.removePassengers();
        this.pendingKill = true;
    }

    private boolean isOnWaterSurface() {
        BlockPos pos = new BlockPos(
                net.minecraft.util.math.MathHelper.floor(this.posX),
                net.minecraft.util.math.MathHelper.floor(this.posY - 0.2D),
                net.minecraft.util.math.MathHelper.floor(this.posZ)
        );

        if (!world.isBlockLoaded(pos)) return true;

        IBlockState feet = world.getBlockState(pos);
        IBlockState below = world.getBlockState(pos.down());

        return BlockRules.isWater(feet) || BlockRules.isWater(below);
    }

    private boolean hasDriver() {
        Entity controller = this.getControllingPassenger();
        return controller != null && !controller.isDead;
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