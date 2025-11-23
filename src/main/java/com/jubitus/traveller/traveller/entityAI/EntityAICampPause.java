package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.traveller.utils.blocks.CampfireBlock;
import com.jubitus.traveller.traveller.utils.blocks.CampfireManager;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

import static com.jubitus.traveller.traveller.entityAI.EntityTraveller.CAMP_RADIUS;

public class EntityAICampPause extends EntityAIBase {
    private static final int NEAR_VILLAGE_RADIUS = 100; // reuse your constant
    private static final int SCAN_RADIUS = 6;           // search for flat spot near the path
    private static final float START_CHANCE_PER_TICK = 0.0008f;
    private final EntityTraveller mob;

    private BlockPos campSpot;          // where the fire is placed
    private int campDuration;
    private int nextSwing;


    private static final double MIN_DISTANCE_FROM_MOB_SQ = 5.0D; // at least 2 blocks away


    @Nullable private BlockPos frontSlot = null;
    private int socialLookTicks = 0;
    @Nullable private EntityTraveller peerNow = null;


    public EntityAICampPause(EntityTraveller mob) {
        this.mob = mob;
        this.setMutexBits(1 | 2); // move + look
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false; // keep only this
        if (mob.isFollowingSomeone()) return false;
        if (mob.isVillageIdling()) return false;
        if (mob.getTargetVillage() == null) return false;
        if (mob.hasCampfire()) return false;
        if (mob.campOnCooldown()) return false;
        if (mob.isCampJoinOnCooldown()) return false;

        BlockPos existing = CampfireManager.get(mob.world)
                .nearestActive(mob.world, mob.getPosition(), 64, mob.getUniqueID(), /*verifyLit*/ false);
        if (existing != null) return false;

        if (EntityTraveller.isNearAnyVillage(mob.world, mob.getPosition(), NEAR_VILLAGE_RADIUS)) return false;
        if (mob.getRNG().nextFloat() > START_CHANCE_PER_TICK) return false;

        BlockPos spot = findCampSpot(mob.getPosition(), SCAN_RADIUS);
        if (spot == null) return false;
        if (CampfireBlock.get() == null) return false;

        this.campSpot = spot;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false; // break camp if attacked

        if (!mob.hasCampfire()) return false;
        BlockPos camp = mob.getCampfirePos();
        if (camp == null) return false;

        IBlockState st = mob.world.getBlockState(camp);
        Block campBlock = CampfireBlock.get();
        if (campBlock == null || st.getBlock() != campBlock) return false;
        if (!CampfireBlock.isLit(st)) return false;

        // stay until our planned leave tick
        return mob.ticksExisted < mob.campLeaveTick;
    }

    @Override
    public void startExecuting() {
        if (mob.world.isRemote) return; // server only
        BlockPos existing = CampfireManager.get(mob.world)
                .nearestActive(mob.world, campSpot, 96, mob.getUniqueID(), /*verifyLit*/ true);
        if (existing != null) {
            // optionally nudge the joiner AI immediately by setting a short cool-down
            mob.setCampCooldown(); // if you have one; otherwise remove
            return;
        }
        IBlockState lit = CampfireBlock.litStateFacingYaw(mob.rotationYaw);
        if (lit == null) return;

        // Only proceed if we really placed the block
        if (canPlaceFireAt(campSpot)) {
            mob.world.setBlockState(campSpot, lit, 3);
            frontSlot = mob.pickFrontOfFire(campSpot);
            if (frontSlot == null) frontSlot = campSpot; // fallback: center
            mob.getNavigator().tryMoveToXYZ(frontSlot.getX()+0.5, frontSlot.getY(), frontSlot.getZ()+0.5, 0.25D);

            // Register with the manager so it auto-cleans even across restarts
            int nearRadius = 28;
            long now = ((WorldServer) mob.world).getTotalWorldTime();
            long ttl = now + (90 * 20); // ~90s initial budget; we’ll refresh below
            CampfireManager.get(mob.world).addOrRefresh(
                    campSpot, mob.dimension, mob.getUniqueID(), ttl, nearRadius
            );

// EXAMPLE: fixed 60–120s with triangular-ish random
            int min = 20 * 30;   // 15s
            int max = 20 * 120;  // 40s
            campDuration = min + mob.getRNG().nextInt(max - min + 1);
            mob.markCampLeader(campSpot, campDuration);
            mob.setCampCooldown();

            // seed first roam + swing
            nextSwing = 20 + mob.getRNG().nextInt(40);
            mob.getNavigator().clearPath();
        }
        if ((mob.ticksExisted & 0x3F) == 0) { // every 64 ticks ≈ 3.2s
            WorldServer ws = (WorldServer) mob.world;
            long now = ws.getTotalWorldTime();

            // extend to "now + margin"; margin slightly > your check cadence
            long newTtl = now + (20 * 10); // keep 10s of life ahead
            int nearRadius = 28;
            CampfireManager.get(mob.world).addOrRefresh(
                    mob.getCampfirePos(), mob.dimension, mob.getUniqueID(), newTtl, nearRadius
            );
        }
    }

    @Override
    public void resetTask() {
        // Leader packs up the camp
        mob.cleanupOwnedCampfireIfAny(); // your existing helper (removes the block and manager entry if owned)
        BlockPos pos = mob.getCampfirePos();
        if (pos != null) {
            // Extra safety: remove from manager even if another mod toggled the block
            CampfireManager.get(mob.world).removeIfManaged(mob.dimension, pos);
            // And nuke the block if it’s still our fire
            Block fire = CampfireBlock.get();
            if (fire != null && mob.world.getBlockState(pos).getBlock() == fire) {
                mob.world.setBlockToAir(pos);
            }
        }
        mob.clearCamp();
        mob.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        // If the block vanished (player broke it), bail
        if (mob.getCampfirePos() == null) { resetTask(); return; }
        IBlockState st = mob.world.getBlockState(mob.getCampfirePos());
        if (!CampfireBlock.isLit(st)) { resetTask(); return; }

        // --- KEEP THIS RUNNING, every ~3s extend TTL ---
        if ((mob.ticksExisted & 0x3F) == 0) { // every 64 ticks
            WorldServer ws = (WorldServer) mob.world;
            long now = ws.getTotalWorldTime();
            long newTtl = now + (20 * 10); // keep 10s ahead
            int nearRadius = 28;
            BlockPos pos = mob.getCampfirePos();
            if (pos != null) {
                CampfireManager.get(ws).addOrRefresh(
                        pos, mob.dimension, mob.getUniqueID(), newTtl, nearRadius
                );
            }
        }
// 1) Approach front slot once; then stand there
        if (frontSlot != null) {
            double d2 = mob.getDistanceSqToCenter(frontSlot);
            if (d2 > 2.0) {
                if (mob.getNavigator().noPath()) {
                    mob.getNavigator().tryMoveToXYZ(frontSlot.getX()+0.5, frontSlot.getY(), frontSlot.getZ()+0.5, 0.25D);
                }
            } else {
                mob.getNavigator().clearPath(); // stand
            }
        }

// 2) Face fire every tick

// 3) Occasional “cooking” swing (unchanged)
        if (--nextSwing <= 0) {
            boolean closeEnough = frontSlot != null && mob.getDistanceSqToCenter(frontSlot) <= 2.0;
            if (closeEnough) {
                mob.world.setEntityState(mob, (byte)4);
                mob.swingArm(EnumHand.MAIN_HAND);
            }
            nextSwing = closeEnough ? (EntityTraveller.CAMP_SWING_EVERY + mob.getRNG().nextInt(25))
                    : (30 + mob.getRNG().nextInt(30));
        }



    }

    // ---- helpers ----

    @Nullable
    private BlockPos findCampSpot(BlockPos around, int r) {
        World w = mob.world;
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos col = new BlockPos(around.getX()+dx, around.getY(), around.getZ()+dz);
                if (!isAreaLoaded(col, 1)) continue;

                BlockPos y = mob.topStandable(col);
                if (y == null) continue;
                if (!canPlaceFireAt(y)) continue;

                // optional: prefer spots with sky (less goofy indoors)
                if (!w.canSeeSky(y)) continue;

                double d2 = around.distanceSq(y);

                // ⬇⬇⬇  new bit: do NOT place right under / too close to the mob
                if (d2 < MIN_DISTANCE_FROM_MOB_SQ) continue;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = y;
                }
            }
        }
        return best;
    }

    private boolean canPlaceFireAt(BlockPos pos) {
        World w = mob.world;
        if (!w.isBlockLoaded(pos) || !w.isBlockLoaded(pos.down())) return false;
        IBlockState below = w.getBlockState(pos.down());
        IBlockState at = w.getBlockState(pos);

        // must support standing and replaceable at target
        boolean replaceable = at.getMaterial().isReplaceable();
        boolean solidBelow =
                below.getBlockFaceShape(w, pos.down(), EnumFacing.UP) == BlockFaceShape.SOLID
                        || below.getBlock() == Blocks.SNOW_LAYER
                        || below.getBlock() == Blocks.GRASS_PATH
                        || below.getBlock() == Blocks.ICE || below.getBlock() == Blocks.PACKED_ICE;

        return replaceable && solidBelow;
    }




    private boolean isAreaLoaded(BlockPos pos, int r) {
        if (!(mob.world instanceof WorldServer)) return mob.world.isAreaLoaded(pos, r);
        WorldServer ws = (WorldServer) mob.world;
        int minCX = (pos.getX()-r) >> 4, maxCX = (pos.getX()+r) >> 4;
        int minCZ = (pos.getZ()-r) >> 4, maxCZ = (pos.getZ()+r) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) for (int cz = minCZ; cz <= maxCZ; cz++) {
            if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
        }
        return true;
    }


    private static float rotlerp(float current, float target, float maxStep) {
        float d = MathHelper.wrapDegrees(target - current);
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return current + d;
    }


    private void tickSocialLook() {
        // If we already have an active social look, keep it for its remaining ticks
        if (socialLookTicks > 0 && peerNow != null && peerNow.isEntityAlive()) {
            // Softly aim at peer
            mob.getLookHelper().setLookPositionWithEntity(peerNow, 30.0F, 30.0F);
            // Nudge torso to follow a bit so it reads as “talking”
            BlockPos peerPos = peerNow.getPosition();
            mob.faceTowards(peerPos, 6.0F, 12.0F);
            socialLookTicks--;
            return;
        }

        // Chance to start a new social glance (~1% per second)
        if (mob.getRNG().nextInt(100) != 0) return;

        BlockPos fire = mob.getCampfirePos();
        if (fire == null) return;

        // Find a peer at the same camp within ~6 blocks
        List<EntityTraveller> peers = mob.world.getEntitiesWithinAABB(EntityTraveller.class,
                new AxisAlignedBB(fire).grow(6.0, 3.0, 6.0),
                e -> e != null && e != mob && e.isEntityAlive() && fire.equals(e.getCampfirePos()));

        if (peers.isEmpty()) return;

        peerNow = peers.get(mob.getRNG().nextInt(peers.size()));
        socialLookTicks = 40 + mob.getRNG().nextInt(40); // 2–4s glance
    }

}