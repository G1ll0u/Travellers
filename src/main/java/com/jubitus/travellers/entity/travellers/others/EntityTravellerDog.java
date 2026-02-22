package com.jubitus.travellers.entity.travellers.others;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.entityAI.follow.AIDogCompanionFollow;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIFollowOwner;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public class EntityTravellerDog extends EntityWolf {
    // === Orphan flow ===
    private static final int ORPHAN_SIT_TIME_TICKS = 10 * 20; // ~20s, tune to taste
    // --- Config knobs ---
    private static final int ORPHAN_SIT_TICKS = 10 * 20;      // ~20s mourn
    private static final int ORPHAN_SCAN_COOLDOWN = 40;       // scan every 2s
    private static final double ADOPT_SCAN_RADIUS = 16.0D;    // search radius

    private final int orphanSinceTick = -1; // -1 = not orphaned / unknown yet
    // --- Orphan state ---
    private boolean orphaned = false;
    private int orphanSitUntil = 0;
    private int orphanScanCooldown = 0;
    // === Despawn tracking (mirror travellers) ===
// === Despawn tracking (mirror travellers) ===
    private int nearUnloadTicks = 0;
    private int farFromPlayersTicks = 0;
    // Travel goal (added below)

    public EntityTravellerDog(World worldIn) {
        super(worldIn);
    }

    /**
     * Small helper for faction-friendly filtering.
     */
    public static boolean canAttackSameAsOwner(EntityTravellerDog dog, EntityLivingBase target) {
        EntityLivingBase owner = dog.getOwner();
        if (owner == null) return false;
        if (target == null || !target.isEntityAlive()) return false;
        if (target == owner || target == dog) return false;

        if (owner instanceof EntityTraveller && target instanceof EntityTraveller) {
            // Fight only across factions (XOR) — same as your travellers
            return ((EntityTraveller) owner).isAggressiveToTravellers() ^ ((EntityTraveller) target).isAggressiveToTravellers();
        }
        if (owner instanceof EntityTravellerRaider) {
            // Raiders: also attack anything in the raiders list (your config)
            if (!(target instanceof EntityTraveller)) {
                return TravellersModConfig.RAIDER_ATTACK_LIST.isListed(target)
                        && !TravellersModConfig.RAIDER_AGGRO_BLACKLIST.isBlacklisted(target);
            }
        }
        // For non-travellers targets just mirror the owner’s chosen target
        return true;
    }

    // Resolve non-player owners (your travellers) as well as players.
    @Override
    @Nullable
    public EntityLivingBase getOwner() {
        // First try vanilla (players)
        EntityLivingBase pl = super.getOwner();
        if (pl != null) return pl;

        UUID id = this.getOwnerId();
        if (id == null) return null;

        // Look for a Traveller or Raider with that UUID in this world
        List<EntityTraveller> ts = this.world.getEntities(EntityTraveller.class,
                e -> e != null && e.isEntityAlive() && id.equals(e.getUniqueID()));
        if (!ts.isEmpty()) return ts.get(0);

        // (optional) cross-dimension lookup if you support that; skip for 1.12 simplicity
        return null;
    }

    private boolean isPlayerOwned() {
        return this.isTamed() && this.getOwner() instanceof EntityPlayer;
    }

    // Simple “is in combat” and “idling” approximations for the dog
    private boolean isInCombatLike() {
        return this.getAttackTarget() != null || this.getRevengeTarget() != null;
    }

    private boolean isVillageIdlingLike() {
        return this.getNavigator() == null || this.getNavigator().noPath();
    }

    /**
     * Called once by the travellers/raiders on death.
     */
    public void onOwnerDied() {
        // mark orphan only once
        if (!this.orphaned) {
            this.orphaned = true;
            this.orphanSitUntil = this.ticksExisted + ORPHAN_SIT_TICKS;
            this.setSitting(true);
            this.setAttackTarget(null);
            this.getNavigator().clearPath();
        }
    }

    @Override
    protected void initEntityAI() {
        super.initEntityAI(); // keeps OwnerHurtByTarget, OwnerHurtTarget, sit, etc.

        List<EntityAITasks.EntityAITaskEntry> copy =
                new java.util.ArrayList<>(this.tasks.taskEntries);
        for (EntityAITasks.EntityAITaskEntry e : copy) {
            if (e.action instanceof EntityAIFollowOwner) {
                this.tasks.removeTask(e.action);
            }
        }
        this.targetTasks.addTask(1, new AIDogDefendOwner(this));
        // New companion follow (priority a bit higher than travel)
        this.tasks.addTask(6, new AIDogCompanionFollow(this, 1.1D)); // match travellers speed

        this.targetTasks.addTask(3, new AIMirrorOwnerAttackTarget(this));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        // Faster than vanilla wolf
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .setBaseValue(0.34D); // or whatever you liked

        // IMPORTANT: how far A* pathfinding is allowed to search
        if (this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE) != null) {
            this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE)
                    .setBaseValue(48.0D); // 48 blocks search radius (tune: 32–64)
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        nbt.setBoolean("Orphaned", orphaned);
        nbt.setInteger("OrphanSitUntil", orphanSitUntil);
        nbt.setInteger("OrphanScanCd", orphanScanCooldown);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        orphaned = nbt.getBoolean("Orphaned");
        orphanSitUntil = nbt.getInteger("OrphanSitUntil");
        orphanScanCooldown = nbt.getInteger("OrphanScanCd");
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();

        if (world.isRemote) return;

        // === your existing orphan logic (unchanged) ===
        boolean noOwner = (getOwner() == null) && isTamed();

        if (noOwner) {
            if (!orphaned) {
                orphaned = true;
                orphanSitUntil = this.ticksExisted + ORPHAN_SIT_TICKS;
                setSitting(true);
                getNavigator().clearPath();
            }

            if (this.ticksExisted >= orphanSitUntil) {
                if (this.isSitting()) this.setSitting(false);

                if (--orphanScanCooldown <= 0) {
                    orphanScanCooldown = ORPHAN_SCAN_COOLDOWN;
                    EntityLivingBase cand = findAdoptionCandidate();
                    if (cand != null) {
                        this.getNavigator().tryMoveToEntityLiving(cand, 1.25D);

                        if (this.getDistance(cand) < 2.2F) {
                            adopt(cand);
                        }
                    }
                }
            }
        } else {
            if (orphaned) {
                orphaned = false;
                orphanSitUntil = 0;
                orphanScanCooldown = 0;
            }
        }

        // === NEW: despawn logic (only when NOT owned by a player) ===
        if (!isPlayerOwned()) {
            checkAndMaybeDespawnAtChunkEdgeDog();
            checkAndMaybeDespawnFarFromPlayersDog();
        } else {
            // ensure protected dogs never accumulate despawn counters
            nearUnloadTicks = 0;
            farFromPlayersTicks = 0;
        }
    }

    /**
     * Make sure dogs don’t despawn once recruited
     */
    @Override
    protected boolean canDespawn() {
        return false;
    }

    /**
     * Prefer raiders/travellers; fall back to players.
     */
    @Nullable
    private EntityLivingBase findAdoptionCandidate() {
        AxisAlignedBB box = this.getEntityBoundingBox().grow(ADOPT_SCAN_RADIUS, 4.0, ADOPT_SCAN_RADIUS);

        List<EntityLivingBase> list = this.world.getEntitiesWithinAABB(
                EntityLivingBase.class, box,
                e -> e != null && e.isEntityAlive()
                        && (e instanceof EntityTravellerRaider
                        || e instanceof EntityTraveller
                        || e instanceof EntityPlayer));

        if (list.isEmpty()) return null;

        EntityLivingBase best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (EntityLivingBase e : list) {
            double pref =
                    (e instanceof EntityTravellerRaider) ? 3.0 :
                            (e instanceof EntityTraveller) ? 2.0 : 1.0; // player
            double distBonus = 1.0 / (1.0 + this.getDistance(e));    // closer is better
            double score = pref + distBonus;

            // Optional: skip creative/spectator players
            if (e instanceof EntityPlayer) {
                EntityPlayer p = (EntityPlayer) e;
                if (p.isCreative() || p.isSpectator()) continue;
            }

            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private void adopt(EntityLivingBase newOwner) {
        // Set vanilla tame/owner fields so all built-in goals (follow/sit/defend) work.
        if (newOwner instanceof EntityPlayer) {
            setTamed(true);
            setOwnerId(newOwner.getUniqueID());
        } else {
            setTamed(true);
            setOwnerId(newOwner.getUniqueID()); // travellers/raiders adoption
        }
        setSitting(false);
        orphaned = false;
        orphanSitUntil = 0;

        // Little feedback
        this.playTameEffect(true);
        this.world.setEntityState(this, (byte) 7); // hearts
    }

    /**
     * Expose to the goal so it only runs when allowed.
     */
    public boolean shouldRunOrphanTravel() {
        return orphanTravelActive() && isOrphanNow(); // still no owner
    }

    /**
     * Dog can start autonomous travel when the sit timeout is over.
     */
    private boolean orphanTravelActive() {
        return orphanSinceTick >= 0 && this.ticksExisted - orphanSinceTick >= ORPHAN_SIT_TIME_TICKS;
    }

    /**
     * Detect “true orphan”: has an owner UUID, but no living owner entity found.
     */
    private boolean isOrphanNow() {
        UUID id = getOwnerId();
        if (id == null) return false; // never had an owner → not our case
        EntityLivingBase resolved = getOwner(); // your override resolves travellers too
        return resolved == null; // couldn't find the entity in-world
    }

    /**
     * Where to go: nearest Millénaire village center.
     */
    @Nullable
    public BlockPos pickNearestVillage() {
        List<BlockPos> centers = MillVillageIndex.getAllVillageCenters(this.world);
        if (centers == null || centers.isEmpty()) return null;
        BlockPos me = this.getPosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (BlockPos c : centers) {
            double d2 = me.distanceSq(c);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = c;
            }
        }
        return best;
    }

    /**
     * Dog version: despawn near the not-loaded edge when travelling,
     * but never if owned by a player.
     */
    private void checkAndMaybeDespawnAtChunkEdgeDog() {
        if (!TravellersModConfig.despawnNearUnloadedChunks) return;
        if (this.world.isRemote) return;
        if (!(this.world instanceof WorldServer)) return;

        // Simple guards like travellers’s:
        if (isInCombatLike()) {
            nearUnloadTicks = 0;
            return;
        }
        // For dogs, treat "has no path" as idling / not actively travelling
        if (isVillageIdlingLike()) {
            nearUnloadTicks = 0;
            return;
        }

        final WorldServer ws = (WorldServer) this.world;

        final BlockPos p = this.getPosition();
        final int r = Math.max(0, TravellersModConfig.despawnProbeRadiusChunks);
        final int cx0 = (p.getX() >> 4) - r;
        final int cx1 = (p.getX() >> 4) + r;
        final int cz0 = (p.getZ() >> 4) - r;
        final int cz1 = (p.getZ() >> 4) + r;

        boolean touchingEdge = false;
        for (int cx = cx0; cx <= cx1 && !touchingEdge; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!ws.getChunkProvider().chunkExists(cx, cz)) {
                    touchingEdge = true;
                    break;
                }
            }
        }

        if (touchingEdge) {
            nearUnloadTicks++;
        } else {
            nearUnloadTicks = 0;
        }

        if (nearUnloadTicks >= TravellersModConfig.despawnEdgeGraceTicks) {
            // Clean fade, but only if not owned by a player.
            if (!isPlayerOwned()) {
                this.setDead();
            } else {
                nearUnloadTicks = 0;
            }
        }
    }

    /**
     * Dog version: despawn when far from all players for too long,
     * but never if owned by a player.
     */
    private void checkAndMaybeDespawnFarFromPlayersDog() {
        if (!TravellersModConfig.despawnWhenFarFromPlayers) return;
        if (this.world.isRemote) return;
        if (!(this.world instanceof WorldServer)) return;

        if (isInCombatLike()) {
            farFromPlayersTicks = 0;
            return;
        }
        if (isVillageIdlingLike()) {
            farFromPlayersTicks = 0;
            return;
        }

        WorldServer ws = (WorldServer) this.world;

        int viewChunks;
        try {
            viewChunks = ws.getMinecraftServer().getPlayerList().getViewDistance();
        } catch (Throwable t) {
            viewChunks = TravellersModConfig.serverViewDistanceFallbackChunks;
        }

        int bubbleChunks = Math.max(0, viewChunks + TravellersModConfig.farExtraChunks);
        int bubbleBlocks = Math.max(TravellersModConfig.farMinDistanceBlocks, bubbleChunks * 16);

        boolean anyNear = ws.getClosestPlayer(this.posX, this.posY, this.posZ, bubbleBlocks, false) != null;

        if (!anyNear) {
            farFromPlayersTicks++;
        } else {
            farFromPlayersTicks = 0;
        }

        if (farFromPlayersTicks >= TravellersModConfig.farGraceTicks) {
            if (!isPlayerOwned()) {
                this.setDead(); // clean fade (no loot)
            } else {
                farFromPlayersTicks = 0;
            }
        }
    }

    public static class AIMirrorOwnerAttackTarget extends EntityAITarget {
        private final EntityTravellerDog dog;

        public AIMirrorOwnerAttackTarget(EntityTravellerDog dog) {
            super(dog, false);
            this.dog = dog;
            this.setMutexBits(1);
        }

        @Override
        public boolean shouldExecute() {
            EntityLivingBase owner = dog.getOwner();
            if (owner == null) return false;

            EntityLivingBase ownerTgt = ownersAttackTarget(owner);
            if (ownerTgt == null) return false;
            if (!EntityTravellerDog.canAttackSameAsOwner(dog, ownerTgt)) return false;

            return dog.getAttackTarget() != ownerTgt;
        }

        @Nullable
        private EntityLivingBase ownersAttackTarget(EntityLivingBase owner) {
            // Only EntityLiving (not plain EntityLivingBase) has getAttackTarget() in 1.12
            return (owner instanceof EntityLiving)
                    ? ((EntityLiving) owner).getAttackTarget()
                    : null;
        }

        @Override
        public boolean shouldContinueExecuting() {
            EntityLivingBase owner = dog.getOwner();
            EntityLivingBase tgt = dog.getAttackTarget();
            if (owner == null || tgt == null || !tgt.isEntityAlive()) return false;

            EntityLivingBase ownerTgt = ownersAttackTarget(owner);
            return ownerTgt == tgt || owner.getDistance(tgt) < 24.0F;
        }

        @Override
        public void startExecuting() {
            EntityLivingBase owner = dog.getOwner();
            if (owner == null) return;

            EntityLivingBase ownerTgt = ownersAttackTarget(owner);
            if (ownerTgt != null && EntityTravellerDog.canAttackSameAsOwner(dog, ownerTgt)) {
                dog.setAttackTarget(ownerTgt);
                dog.setSitting(false);
                dog.getNavigator().clearPath();
            }
            super.startExecuting();
        }
    }

    public static class AIDogDefendOwner extends EntityAITarget {
        private final EntityTravellerDog dog;
        private EntityLivingBase attacker;
        private int lastRevengeTimer;

        public AIDogDefendOwner(EntityTravellerDog dog) {
            super(dog, false);
            this.dog = dog;
            this.setMutexBits(1);
        }

        @Override
        public boolean shouldExecute() {
            EntityLivingBase owner = dog.getOwner();
            if (owner == null) return false;

            // Sitting wolves/tameables don't defend/attack in vanilla
            if (dog.isSitting()) return false;

            EntityLivingBase revenge = owner.getRevengeTarget(); // <-- "who hurt me"
            if (revenge == null || !revenge.isEntityAlive()) return false;

            int rt = owner.getRevengeTimer();
            if (rt == lastRevengeTimer) return false; // same attacker event as last time

            if (!EntityTravellerDog.canAttackSameAsOwner(dog, revenge)) return false;
            if (!this.isSuitableTarget(revenge, false)) return false;

            attacker = revenge;
            return true;
        }

        @Override
        public void startExecuting() {
            dog.setAttackTarget(attacker);
            dog.setSitting(false);
            dog.getNavigator().clearPath();

            EntityLivingBase owner = dog.getOwner();
            if (owner != null) lastRevengeTimer = owner.getRevengeTimer();

            super.startExecuting();
        }
    }

}

