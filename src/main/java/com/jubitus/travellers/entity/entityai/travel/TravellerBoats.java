package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The boat gestures shared by the travel AI and the follow AI: board (a spawned boat or an existing
 * one), steer, tell whether a boat still floats, and leave. Boarding and leaving are the only places
 * a bonded horse is switched to LED (towed, swimming behind) and back to MOUNTED.
 */
public final class TravellerBoats {

    private TravellerBoats() {
    }

    /** True while the boat sits on (or just in) water; false once beached. Never loads a chunk. */
    public static boolean isOnWater(World world, EntityBoat boat) {
        BlockPos pos = new BlockPos(MathHelper.floor(boat.posX), MathHelper.floor(boat.posY - 0.2D), MathHelper.floor(boat.posZ));
        if (!world.isBlockLoaded(pos)) return false;
        return BlockRules.isVanillaWater(world.getBlockState(pos)) || BlockRules.isVanillaWater(world.getBlockState(pos.down()));
    }

    public static float yawToward(double dx, double dz) {
        return (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
    }

    /** Drives the boat toward (x, z) at {@code speed} blocks/tick, boat and rider facing the way. */
    public static void steer(EntityTraveller rider, EntityBoat boat, double x, double z, double speed) {
        double dx = x - boat.posX;
        double dz = z - boat.posZ;
        double distSq = dx * dx + dz * dz;
        if (distSq < 0.05D) {
            boat.motionX = 0.0D;
            boat.motionZ = 0.0D;
            return;
        }
        double dist = Math.sqrt(distSq);
        boat.motionX = dx / dist * speed;
        boat.motionZ = dz / dist * speed;
        float yaw = yawToward(dx, dz);
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;
        rider.rotationYaw = yaw;
        rider.rotationYawHead = yaw;
        rider.renderYawOffset = yaw;
    }

    /** Spawns a travel boat at (x, y, z) facing {@code yaw} and boards it; null (nothing left behind) on refusal. */
    @Nullable
    public static EntityTravellerBoat spawnAndBoard(EntityTraveller rider, double x, double y, double z, float yaw) {
        World w = rider.world;
        if (w.isRemote) return null;
        EntityTravellerBoat boat = new EntityTravellerBoat(w, x, y, z);
        boat.setLocationAndAngles(x, y, z, yaw, boat.rotationPitch);
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;
        rider.rotationYaw = yaw;
        rider.rotationYawHead = yaw;
        rider.renderYawOffset = yaw;
        if (!w.spawnEntity(boat)) {
            boat.setDead();
            return null;
        }
        if (!board(rider, boat)) {
            boat.setDead();
            return null;
        }
        return boat;
    }

    /**
     * Boards {@code boat}. A bonded horse is switched to LED first: vanilla holds one vehicle at a
     * time, so boarding would silently drop the horse, and it is towed across on its leash instead.
     */
    public static boolean board(EntityTraveller rider, EntityBoat boat) {
        MountMode before = rider.getMountMode();
        if (rider.getMountUUID() != null && before != MountMode.LED) {
            com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(rider,
                    com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.BOAT);
        }
        TravellerMountSupport.setMountMode(rider, MountMode.LED);
        if (!rider.startRiding(boat, true)) {
            TravellerMountSupport.setMountMode(rider, before);
            return false;
        }
        rider.getNavigator().clearPath();
        rider.setSprinting(false);
        return true;
    }

    /** Steps out of the boat; a travel boat is removed (never left abandoned), the saddle is asked back. */
    public static void leave(EntityTraveller rider) {
        if (!rider.isRiding() || !(rider.getRidingEntity() instanceof EntityBoat)) return;
        Entity boat = rider.getRidingEntity();
        rider.dismountRidingEntity();
        if (boat instanceof EntityTravellerBoat) ((EntityTravellerBoat) boat).requestKill();
        TravellerMountSupport.setMountMode(rider, MountMode.MOUNTED);
    }
}
