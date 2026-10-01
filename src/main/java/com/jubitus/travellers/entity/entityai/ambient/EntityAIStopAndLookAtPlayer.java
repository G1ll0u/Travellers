package com.jubitus.travellers.entity.entityai.ambient;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.mill.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.*;

public class EntityAIStopAndLookAtPlayer extends EntityAIBase {
    private final EntityTraveller traveller;
    private final double triggerRangeSq;
    private final float yawLimit;
    private final float pitchLimit;

    private EntityPlayer target;

    public EntityAIStopAndLookAtPlayer(EntityTraveller traveller, double triggerRange, float yawLimit, float pitchLimit) {
        this.traveller = traveller;
        this.triggerRangeSq = triggerRange * triggerRange;
        this.yawLimit = yawLimit;
        this.pitchLimit = pitchLimit;
        this.setMutexBits(2); // 1=MOVE, 2=LOOK
    }

    @Override
    public boolean shouldExecute() {
        if (traveller.isSleepingInBed()) return false;
        if (isDisabledForHiredWorker()) return false;
        if (traveller.world.isRemote) return false;
        if (traveller.hasFollowLeader()) return false;
        if (traveller.isInCombat()) return false;
        if (traveller.isAutoEating()) return false;
        // A village visitor handles nearby players itself (a glance, or stopping if they stay close).
        if (traveller.isVisitingVillage()) return false;

        // NEW: ignore while a player is mounted
        if (traveller.hasPlayerPassenger()) return false;

        EntityPlayer p = traveller.world.getClosestPlayerToEntity(traveller, Math.sqrt(triggerRangeSq));
        if (p == null || !p.isEntityAlive() || p.isSpectator()) return false;

        double dSq = traveller.getDistanceSq(p);
        if (dSq > triggerRangeSq) return false;

        if (!traveller.getEntitySenses().canSee(p)) return false;
        if (this.traveller instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.traveller;
            if (w.isHiredFollower() && !w.isJobActive()) {
                // When acting as a follower (and not on a job),
                // let the follow AI control head/eyes, not this one.
                return false;
            }
        }
        this.target = p;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (traveller.isSleepingInBed()) return false;
        if (isDisabledForHiredWorker()) return false;
        if (traveller.hasFollowLeader()) return false;
        if (traveller.isInCombat() || traveller.isAutoEating()) return false;
        if (traveller.hasPlayerPassenger()) return false; // NEW


        return target != null && target.isEntityAlive()
                && traveller.getDistanceSq(target) <= triggerRangeSq
                && traveller.getEntitySenses().canSee(target);

    }

    @Override
    public void startExecuting() {
        // Don't force a stop if someone is riding
        if (!traveller.hasPlayerPassenger()) {
            traveller.getNavigator().clearPath();
        }
    }

    @Override
    public void resetTask() {
        this.target = null;
    }

    @Override
    public void updateTask() {
        if (traveller.isInCombat() || traveller.hasFollowLeader()) return;
        if (traveller.hasPlayerPassenger()) return; // NEW: do nothing while mounted

        // Optional: only keep this if you want them to stop when looking
        traveller.getNavigator().clearPath();

        traveller.getLookHelper().setLookPositionWithEntity(target, yawLimit, pitchLimit);
    }

    private boolean isDisabledForHiredWorker() {
        if (this.traveller instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.traveller;
            // Hired follower in companion mode, or at work at its duty zone (the duty AI owns it)
            return w.isHiredFollower() && (!w.isJobActive() || w.isOnDuty());
        }
        return false;
    }
}
