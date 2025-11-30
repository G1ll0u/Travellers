package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.Travellers;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;

import javax.annotation.Nullable;

public class EntityTravellerMerchant extends EntityTraveller {

    // How many chunks around the merchant stay loaded
    private static final int CHUNK_RADIUS = 3;
    private ForgeChunkManager.Ticket chunkTicket;
    private ChunkPos forcedChunk;

    @Nullable
    private final java.util.Set<net.minecraft.util.math.ChunkPos> forcedChunks =
            new java.util.HashSet<>();

    // --- DEBUG / STATUS FIELDS ---
    private double lastDebugDistSqToTarget = Double.MAX_VALUE;
    private int ticksSinceDebugProgress = 0;

    public EntityTravellerMerchant(World worldIn) {
        super(worldIn);
        this.enablePersistence(); // vanilla: mark as persistent so it never despawns
    }

    @Override
    protected boolean canDespawn() {
        return false; // absolutely never despawn
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!this.world.isRemote) {
            ensureChunkTicket();
            updateForcedChunk();
            updateDebugProgress();
        }
    }

    // --- small helper to track progress toward target ---
    private void updateDebugProgress() {
        BlockPos tv = this.getTargetVillage();
        if (tv == null) {
            // not travelling / no target
            lastDebugDistSqToTarget = Double.MAX_VALUE;
            ticksSinceDebugProgress = 0;
            return;
        }

        double d2 = this.getDistanceSqToCenter(tv);

        // "significant" improvement: ~0.7 blocks closer (distance^2 drop > 0.5)
        if (d2 + 0.5 < lastDebugDistSqToTarget) {
            lastDebugDistSqToTarget = d2;
            ticksSinceDebugProgress = 0;
        } else {
            ticksSinceDebugProgress++;
        }
    }

    // ----------------------------------------------------------------------
    // Exposed debug getters for the command
    // ----------------------------------------------------------------------

    /** True if this merchant currently has an active chunk ticket + forced chunk. */
    public boolean isChunkLoadingActive() {
        return chunkTicket != null && forcedChunk != null;
    }

    /** Chunk position currently being forced, or null. */
    @Nullable
    public ChunkPos getForcedChunk() {
        return forcedChunk;
    }

    /** squared distance to current target used for debug; Double.MAX_VALUE if none. */
    public double getLastDebugDistSqToTarget() {
        return lastDebugDistSqToTarget;
    }

    /** How many ticks since we last got closer to the target. */
    public int getTicksSinceDebugProgress() {
        return ticksSinceDebugProgress;
    }


    // ----------------------------------------------------------------------
    // Chunk loading helpers
    // ----------------------------------------------------------------------

    private void ensureChunkTicket() {
        if (chunkTicket != null || world.isRemote) return;
        if (!(world instanceof WorldServer)) return;

        chunkTicket = ForgeChunkManager.requestTicket(
                Travellers.INSTANCE,
                (WorldServer) world,
                ForgeChunkManager.Type.ENTITY
        );

        if (chunkTicket != null) {
            chunkTicket.bindEntity(this);
            chunkTicket.getModData().setString("TravellerMerchant", this.getUniqueID().toString());
        }
    }


    private void updateForcedChunk() {
        if (chunkTicket == null || !(world instanceof WorldServer)) return;

        ChunkPos center = new ChunkPos(this.getPosition());

        if (forcedChunk == null || !forcedChunk.equals(center)) {
            forcedChunk = center;

            // Unforce old
            for (ChunkPos pos : forcedChunks) {
                ForgeChunkManager.unforceChunk(chunkTicket, pos);
            }
            forcedChunks.clear();

            // Force new radius
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                    ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                    ForgeChunkManager.forceChunk(chunkTicket, cp);
                    forcedChunks.add(cp);
                }
            }
        }
    }

    @Override
    public void setDead() {
        super.setDead();
        if (!world.isRemote && chunkTicket != null) {
            for (ChunkPos pos : forcedChunks) {
                ForgeChunkManager.unforceChunk(chunkTicket, pos);
            }
            forcedChunks.clear();
            ForgeChunkManager.releaseTicket(chunkTicket);
            chunkTicket = null;
            forcedChunk = null;
        }
    }



    private void releaseChunkTicket() {
        if (chunkTicket == null) return;

        for (ChunkPos pos : forcedChunks) {
            ForgeChunkManager.unforceChunk(chunkTicket, pos);
        }
        forcedChunks.clear();

        ForgeChunkManager.releaseTicket(chunkTicket);
        chunkTicket = null;
    }


    // ----------------------------------------------------------------------
    // AI: reuse the travel AI
    // ----------------------------------------------------------------------

    @Override
    protected void initEntityAI() {
        super.initEntityAI();
        // your travel AI is already installed in EntityTraveller.initEntityAI()
    }

    @Override
    public boolean isNoDespawnRequired() {
        return true;
    }

    @Override
    protected void despawnEntity() {
        // Do nothing: merchants are permanent
    }
}