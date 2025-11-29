package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.camp.CampfireBlock;
import com.jubitus.traveller.traveller.camp.CampfireManager;
import net.minecraft.block.Block;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

public class TravellerCamp {

    private final EntityTraveller owner;

    // --- Camp state ---
    final Map<BlockPos, Integer> campAvoidUntil = new HashMap<>();
    public long campLeaveTime = 0L;   // world time, not ticksExisted
    public long campLeaveTick;
    int campRoamCooldown = 0;         // next time to pick a roam target
    int campSwingTimer = 0;
    int campGlobalCooldown = 0;       // leader cooldown gate
    int campJoinCooldown = 0;

    @Nullable
    BlockPos campfirePos = null;
    boolean campLeader = false;

    // --- Camp social state ---
    @Nullable
    EntityTraveller socialPeer = null;
    int socialTicks = 0;
    int socialCooldown = 0;

    public TravellerCamp(EntityTraveller owner) {
        this.owner = owner;
    }

    // ----------------- Basic camp API -----------------

    public boolean isCampLeader() {
        return campLeader;
    }

    public void setCampLeader(boolean campLeader) {
        this.campLeader = campLeader;
    }

    public boolean hasCampfire() {
        return campfirePos != null;
    }

    @Nullable
    public BlockPos getCampfirePos() {
        return campfirePos;
    }

    public boolean isCampJoinOnCooldown() {
        return campJoinCooldown > 0;
    }

    public void setCampJoinCooldown() {
        this.campJoinCooldown = TravellersModConfig.CAMP_JOIN_COOLDOWN_TICKS;
    }

    public void setCampCooldown() {
        this.campGlobalCooldown = TravellersModConfig.CAMP_RANDOM_COOLDOWN;
    }

    public boolean campOnCooldown() {
        return campGlobalCooldown > 0;
    }

    // ----------------- Avoid / blacklist logic -----------------

    /** Call every tick from onLivingUpdate to decay the avoid map. */
    public void tickCampAvoid() {
        if (campAvoidUntil.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Integer>> it = campAvoidUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> e = it.next();
            int t = e.getValue() - 1;
            if (t <= 0) it.remove();
            else e.setValue(t);
        }
    }

    /** Mark a camp to avoid for N ticks. */
    public void avoidCamp(BlockPos pos, int ticks) {
        if (pos != null) campAvoidUntil.put(pos.toImmutable(), ticks);
    }

    /** True if this camp is currently blacklisted for this traveller. */
    public boolean isAvoidingCamp(BlockPos pos) {
        if (pos == null) return false;
        Integer left = campAvoidUntil.get(pos);
        return left != null && left > 0;
    }

    // ----------------- Camp join / leave -----------------

    public void markCampLeader(BlockPos pos, int durationTicks) {
        this.campfirePos = pos.toImmutable();
        if (owner.world instanceof WorldServer) {
            long now = ((WorldServer) owner.world).getTotalWorldTime();
            this.campLeaveTime = now + durationTicks;
        } else {
            // FIX 1: use the field on Entity, not a non-existent getter
            this.campLeaveTime = owner.ticksExisted + durationTicks;
        }
        this.campLeader = true;
    }

    // joiner
    public void joinCampAt(BlockPos pos, int durationTicks) {
        this.campfirePos = pos.toImmutable();
        if (owner.world instanceof WorldServer) {
            long now = ((WorldServer) owner.world).getTotalWorldTime();
            this.campLeaveTime = now + durationTicks;
        } else {
            this.campLeaveTime = owner.ticksExisted + durationTicks;
        }
        this.campLeader = false;
    }

    public void clearCamp() {
        this.campfirePos = null;
        this.campLeader = false;
        this.campLeaveTick = 0;
        this.campRoamCooldown = 0;
        setCampJoinCooldown();
    }

    void cleanupOwnedCampfireIfAny() {
        if (!campLeader || campfirePos == null || owner.world.isRemote) return;
        WorldServer ws = (WorldServer) owner.world;
        Block fire = CampfireBlock.get();
        if (fire != null && ws.getBlockState(campfirePos).getBlock() == fire) {
            ws.setBlockToAir(campfirePos);
        }
        CampfireManager.get(ws).remove(campfirePos, owner.dimension); // uses owner.dimension
    }

    // ----------------- Social / glance logic -----------------

    public boolean isSocialLooking() {
        if (socialTicks <= 0 || socialPeer == null || !socialPeer.isEntityAlive()) return false;

        // make sure both travellers are actually at the same campfire
        TravellerCamp peerCamp = socialPeer.getCamp();
        return Objects.equals(this.campfirePos, peerCamp.campfirePos);
    }

    public void beginSocialLook(EntityTraveller peer, int ticks) {
        if (peer == null || peer == owner) return;
        this.socialPeer = peer;
        this.socialTicks = Math.max(1, ticks);
        this.socialCooldown = 40 + owner.getRNG().nextInt(60);
    }

    public void endSocialLook() {
        this.socialTicks = 0;
        this.socialPeer = null;
    }

    public void tickCampSocial() {
        if (socialCooldown > 0) socialCooldown--;

        // maintain active glance
        if (isSocialLooking()) {
            // eyes
            owner.getLookHelper().setLookPositionWithEntity(socialPeer, 30.0F, 30.0F);
            // gentle torso follow so it reads as "chatting"
            BlockPos p = socialPeer.getPosition();
            owner.faceTowards(p, 6.0F, 12.0F);

            if (--socialTicks <= 0) endSocialLook();
            return;
        }

        // (idle) occasionally start a new glance
        if (socialCooldown > 0) return;

        // ~2% per second: adjust to taste
        if (owner.getRNG().nextInt(50) != 0) return;

        BlockPos camp = this.campfirePos;
        if (camp == null) return;

        List<EntityTraveller> peers = owner.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                new AxisAlignedBB(camp).grow(6.0, 3.0, 6.0),
                e -> e != null && e != owner && e.isEntityAlive()
                        && Objects.equals(camp, e.getCamp().getCampfirePos())
        );
        if (peers.isEmpty()) return;

        EntityTraveller peer = peers.get(owner.getRNG().nextInt(peers.size()));
        TravellerCamp peerCamp = peer.getCamp();

        // FIX 2: don't touch peer.socialPeer directly – go through its camp helper
        if (peerCamp.isSocialLooking() && peerCamp.socialPeer != owner) return;

        int dur = 40 + owner.getRNG().nextInt(40); // 2–4 s
        this.beginSocialLook(peer, dur);

        // make it mutual most of the time
        if (!peerCamp.isSocialLooking() || peerCamp.socialPeer == owner) {
            peerCamp.beginSocialLook(owner, dur - owner.getRNG().nextInt(10));
        }

        // cooldown before starting another new glance
        this.socialCooldown = 60 + owner.getRNG().nextInt(80);
    }
}
