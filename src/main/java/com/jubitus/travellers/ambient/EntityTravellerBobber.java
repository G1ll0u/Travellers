package com.jubitus.travellers.ambient;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

import javax.annotation.Nullable;

/**
 * Cosmetic bobber of a fishing traveller: flies in an arc from the rod to its target, floats and dips
 * on tugs. Its motion is computed from its age on both sides, so it needs no position sync.
 */
// INVARIANT: never saved and dies on its own when its owner stops holding a rod, so none can be left behind.
public class EntityTravellerBobber extends Entity implements IEntityAdditionalSpawnData {

    private static final int FLIGHT_TICKS = 12;
    private static final double ARC_HEIGHT = 1.5;
    private static final int DIP_TICKS = 8;
    private static final double DIP_DEPTH = 0.25;
    private static final byte DIP_EVENT = 70;
    private static final double MAX_OWNER_DIST_SQ = 16 * 16;

    private int ownerId = -1;
    private double startX, startY, startZ;
    private double targetX, targetY, targetZ;
    private int dipTicks;

    public EntityTravellerBobber(World world) {
        super(world);
        setSize(0.25F, 0.25F);
        noClip = true;
        ignoreFrustumCheck = true;
    }

    public EntityTravellerBobber(World world, EntityLivingBase owner, Vec3d target) {
        this(world);
        ownerId = owner.getEntityId();
        Vec3d tip = rodTip(owner, 1.0F);
        startX = tip.x;
        startY = tip.y;
        startZ = tip.z;
        targetX = target.x;
        targetY = target.y;
        targetZ = target.z;
        setPosition(startX, startY, startZ);
    }

    /** Where the rod tip, hence the line, starts: the vanilla third-person formula for a player's hook. */
    public static Vec3d rodTip(EntityLivingBase owner, float partialTicks) {
        double side = (owner.getPrimaryHand() == EnumHandSide.RIGHT ? 1 : -1) * 0.35;
        float yaw = (owner.prevRenderYawOffset + (owner.renderYawOffset - owner.prevRenderYawOffset) * partialTicks) * 0.017453292F;
        double sin = MathHelper.sin(yaw);
        double cos = MathHelper.cos(yaw);
        double x = owner.prevPosX + (owner.posX - owner.prevPosX) * partialTicks;
        double y = owner.prevPosY + (owner.posY - owner.prevPosY) * partialTicks;
        double z = owner.prevPosZ + (owner.posZ - owner.prevPosZ) * partialTicks;
        return new Vec3d(x - cos * side - sin * 0.8, y + owner.getEyeHeight() - 0.45, z - sin * side + cos * 0.8);
    }

    @Nullable
    public EntityLivingBase getOwner() {
        Entity e = world.getEntityByID(ownerId);
        return e instanceof EntityLivingBase ? (EntityLivingBase) e : null;
    }

    /** Server side: makes every watching client dip the bobber once. */
    public void dip() {
        world.setEntityState(this, DIP_EVENT);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!world.isRemote && !ownerStillFishing()) {
            setDead();
            return;
        }
        if (dipTicks > 0) dipTicks--;

        float t = ticksExisted / (float) FLIGHT_TICKS;
        if (t < 1.0F) {
            setPosition(startX + (targetX - startX) * t,
                    startY + (targetY - startY) * t + ARC_HEIGHT * 4.0 * t * (1.0F - t),
                    startZ + (targetZ - startZ) * t);
        } else {
            double dip = dipTicks > 0 ? DIP_DEPTH * Math.sin(Math.PI * dipTicks / DIP_TICKS) : 0.0;
            setPosition(targetX, targetY + 0.04 * Math.sin(ticksExisted * 0.2) - dip, targetZ);
        }
    }

    private boolean ownerStillFishing() {
        EntityLivingBase owner = getOwner();
        return owner != null && owner.isEntityAlive() && getDistanceSq(owner) < MAX_OWNER_DIST_SQ
                && owner.getHeldItemMainhand().getItem() instanceof ItemFishingRod;
    }

    @Override
    public void handleStatusUpdate(byte id) {
        if (id != DIP_EVENT) {
            super.handleStatusUpdate(id);
            return;
        }
        dipTicks = DIP_TICKS;
        for (int i = 0; i < 6; i++) {
            world.spawnParticle(EnumParticleTypes.WATER_SPLASH, posX + (rand.nextDouble() - 0.5) * 0.4,
                    targetY + 0.1, posZ + (rand.nextDouble() - 0.5) * 0.4, 0.0, 0.05, 0.0);
        }
    }

    // Position comes from the age; tracker packets would only make it jitter.
    @Override
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int increments, boolean teleport) {
    }

    @Override
    public boolean isInRangeToRenderDist(double distanceSq) {
        return distanceSq < 64.0 * 64.0;
    }

    @Override
    protected void entityInit() {
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
    }

    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;
    }

    @Override
    public boolean writeToNBTAtomically(NBTTagCompound tag) {
        return false;
    }

    @Override
    public void writeSpawnData(ByteBuf buf) {
        buf.writeInt(ownerId);
        buf.writeInt(ticksExisted);
        buf.writeDouble(startX);
        buf.writeDouble(startY);
        buf.writeDouble(startZ);
        buf.writeDouble(targetX);
        buf.writeDouble(targetY);
        buf.writeDouble(targetZ);
    }

    @Override
    public void readSpawnData(ByteBuf buf) {
        ownerId = buf.readInt();
        ticksExisted = buf.readInt();
        startX = buf.readDouble();
        startY = buf.readDouble();
        startZ = buf.readDouble();
        targetX = buf.readDouble();
        targetY = buf.readDouble();
        targetZ = buf.readDouble();
    }
}
