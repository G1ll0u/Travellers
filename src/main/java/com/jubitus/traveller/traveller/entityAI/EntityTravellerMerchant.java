package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.Travellers;
import com.jubitus.traveller.traveller.pathing.TravellerChunkManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;

import javax.annotation.Nullable;

public class EntityTravellerMerchant extends EntityTraveller {

    public EntityTravellerMerchant(World worldIn) {
        super(worldIn);
        this.enablePersistence(); // vanilla: mark as persistent so it never despawns
    }

    @Override
    protected boolean canDespawn() {
        return false;
    }

    @Override
    public boolean isNoDespawnRequired() {
        return true;
    }

    @Override
    protected void despawnEntity() {
        // NO-OP: merchants are permanent
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!this.world.isRemote) {
            TravellerChunkManager.INSTANCE.onMerchantTick(this);
        }
    }

    @Override
    public void setDead() {
        // Don't untrack here – this is called during world unload too.
        super.setDead();
    }
    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            TravellerChunkManager.INSTANCE.untrackMerchant(this);
        }
        super.onDeath(cause);
    }
    // If you want, you can override checkAndMaybeDespawn* in EntityTraveller to NO-OP
    // OR guard them with "instanceof EntityTravellerMerchant" inside the base class
}
