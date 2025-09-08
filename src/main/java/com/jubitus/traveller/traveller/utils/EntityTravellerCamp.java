package com.jubitus.traveller.traveller.utils;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

public class EntityTravellerCamp extends Entity {
    private static final int DEFAULT_LIFETIME = 20 * 25; // ~25s
    private int lifetime;
    private java.util.List<BlockPos> torchSpots = new java.util.ArrayList<>();
    @Nullable
    private java.util.UUID owner; // first traveller who created it

    public EntityTravellerCamp(World worldIn) {
        super(worldIn);
        this.noClip = true;
        this.isImmuneToFire = true;
        this.setSize(0.1F, 0.1F); // tiny/invisible
        this.lifetime = DEFAULT_LIFETIME;
    }

    public EntityTravellerCamp(World world, BlockPos center, @Nullable UUID owner, int lifetimeTicks) {
        this(world);
        this.setPosition(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        this.owner = owner;
        this.lifetime = Math.max(60, lifetimeTicks);
    }

    @Override
    protected void entityInit() {}

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        this.lifetime = nbt.getInteger("Lifetime");
        if (nbt.hasUniqueId("Owner")) this.owner = nbt.getUniqueId("Owner");
        this.torchSpots.clear();
        int[] arr = nbt.getIntArray("TorchSpots"); // packed triples x,y,z
        for (int i = 0; i + 2 < arr.length; i += 3) {
            this.torchSpots.add(new BlockPos(arr[i], arr[i+1], arr[i+2]));
        }
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setInteger("Lifetime", lifetime);
        if (owner != null) nbt.setUniqueId("Owner", owner);
        int[] arr = new int[torchSpots.size() * 3];
        for (int i = 0, j = 0; i < torchSpots.size(); i++) {
            BlockPos p = torchSpots.get(i);
            arr[j++] = p.getX(); arr[j++] = p.getY(); arr[j++] = p.getZ();
        }
        nbt.setIntArray("TorchSpots", arr);
    }

    public void addTorchSpot(BlockPos p) { this.torchSpots.add(p); }

    @Nullable public UUID getOwner() { return owner; }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (world.isRemote) return;

        if (--lifetime <= 0) {
            cleanupTorches();
            this.setDead();
        }
    }

    private void cleanupTorches() {
        for (BlockPos p : torchSpots) {
            IBlockState st = world.getBlockState(p);
            if (st.getBlock() == net.minecraft.init.Blocks.TORCH) {
                world.destroyBlock(p, true); // drop item
            }
        }
        torchSpots.clear();
    }

    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean canBeAttackedWithItem() { return false; }
}

