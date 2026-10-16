package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.debug.autotest.AutotestInvariants;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * A hired group on a Go order loses its leader: the best-equipped follower takes the lead and the Go order, the
 * other follower follows it, and the group gets closer to the destination. Variants: the leader dies loaded, or
 * dies offscreen (as in a virtual battle).
 */
public final class WorkerSuccessionScenario extends AutotestScenario {

    private static final int RUN_LENGTH = 30;
    private static final long KILL_AFTER_TICKS = 40L;
    private static final double PROGRESS_BLOCKS = 6.0D;

    private final boolean virtual;
    private UUID leaderId;
    private UUID strongId;
    private UUID weakId;
    private BlockPos goal;
    private boolean killed;
    private boolean orderChecked;
    private double startDistance;

    public WorkerSuccessionScenario(boolean virtual) {
        this.virtual = virtual;
    }

    @Override
    public String name() {
        return virtual ? "worker-succession-virtual" : "worker-succession";
    }

    @Override
    public int budgetSeconds() {
        return 120;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        BlockPos[] run = arena.findRun(RUN_LENGTH, attempt);
        if (run == null) return Outcome.skip("no open " + RUN_LENGTH + "-block run near " + arena.centerX + " " + arena.centerZ);
        goal = run[1];
        killed = false;
        orderChecked = false;

        EntityTravellerWorker leader = spawnBare(ws, arena.groundAt(run[0].getX(), run[0].getZ()));
        EntityTravellerWorker strong = spawnBare(ws, arena.groundAt(run[0].getX() + 1, run[0].getZ()));
        EntityTravellerWorker weak = spawnBare(ws, arena.groundAt(run[0].getX() + 2, run[0].getZ()));
        if (leader == null || strong == null || weak == null) return Outcome.fail("a worker spawn was refused");
        leaderId = leader.getUniqueID();
        strongId = strong.getUniqueID();
        weakId = weak.getUniqueID();

        strong.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        strong.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        strong.setItemStackToSlot(EntityEquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        strong.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        strong.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        // Same links WorkerGroupService.assignToLeader + leaderGoTo set, without their player-only epilogue.
        for (EntityTravellerWorker w : new EntityTravellerWorker[]{strong, weak}) {
            w.setEscortLeaderUUID(leaderId);
            w.setFollowOrderActive(true);
        }
        leader.setFollowOrderActive(false);
        leader.trips.startGoTo(goal, leader.dimension);

        if (!virtual) return null;

        VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
        long now = ws.getTotalWorldTime();
        for (EntityTravellerWorker w : new EntityTravellerWorker[]{leader, strong, weak}) {
            if (!vtm.virtualizeWorker(ws, w, now)) return Outcome.fail("virtualizing " + w.getUniqueID() + " was refused");
        }
        VirtualTravellerRecord leaderRec = vtm.getRecord(leaderId);
        if (leaderRec == null) return Outcome.fail("the leader has no record after virtualizing");
        // Same order as VirtualBattleManager.killVirtual.
        WorkerGroupService.onLeaderDied(ws.getMinecraftServer(), leaderRec.followOwnerUUID, leaderId,
                WorkerGroupService.captureOrder(leaderRec));
        AutotestInvariants.onVirtualDeath(leaderId);
        vtm.removeRecord(ws, leaderId);
        killed = true;
        return null;
    }

    @Nullable
    private EntityTravellerWorker spawnBare(WorldServer ws, @Nullable BlockPos p) {
        if (p == null) return null;
        EntityTravellerWorker w = spawnHiredWorker(ws, p);
        if (w == null) return null;
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) w.setItemStackToSlot(slot, ItemStack.EMPTY);
        for (int i = 0; i < w.inventory.getSlots(); i++) w.inventory.setStackInSlot(i, ItemStack.EMPTY);
        return w;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        if (!killed) {
            if (ageTicks < KILL_AFTER_TICKS) return Outcome.PENDING;
            EntityTravellerWorker leader = worker(ws, leaderId);
            if (leader == null) return Outcome.fail("the leader left the loaded world before it could be killed");
            leader.attackEntityFrom(DamageSource.OUT_OF_WORLD, 10000.0F);
            killed = true;
            return Outcome.PENDING;
        }
        if (position(ws, strongId) == null) return Outcome.fail("the best-equipped follower no longer exists");
        if (position(ws, weakId) == null) return Outcome.fail("the other follower no longer exists");
        if (escortOf(ws, strongId) != null || !strongId.equals(escortOf(ws, weakId))) return Outcome.PENDING;

        if (!orderChecked) {
            BlockPos target = goTarget(ws, strongId);
            if (!goal.equals(target)) return Outcome.fail("new leader's Go order is " + target + ", the old leader's was " + goal);
            orderChecked = true;
            startDistance = horizontalDistance(position(ws, strongId), goal);
        }
        double now = horizontalDistance(position(ws, strongId), goal);
        return startDistance - now >= PROGRESS_BLOCKS || now <= PROGRESS_BLOCKS ? Outcome.PASS : Outcome.PENDING;
    }

    @Nullable
    private static EntityTravellerWorker worker(WorldServer ws, UUID id) {
        return loaded(ws, id) instanceof EntityTravellerWorker ? (EntityTravellerWorker) loaded(ws, id) : null;
    }

    @Nullable
    private static UUID escortOf(WorldServer ws, UUID id) {
        EntityTravellerWorker w = worker(ws, id);
        if (w != null) return w.getEscortLeaderUUID();
        VirtualTravellerRecord r = record(ws, id);
        return r == null ? null : r.escortLeaderUUID;
    }

    @Nullable
    private static BlockPos goTarget(WorldServer ws, UUID id) {
        EntityTravellerWorker w = worker(ws, id);
        if (w != null) return w.trips.isGoOrderActive() ? w.trips.getGoOrderTargetPos() : null;
        VirtualTravellerRecord r = record(ws, id);
        return r != null && r.goOrderActive ? r.goal : null;
    }

    @Override
    public String describeState(WorldServer ws) {
        return String.format(Locale.ROOT, "leader %s killed=%s; strong %s escort=%s go=%s; weak %s escort=%s",
                where(ws, leaderId), killed, where(ws, strongId), escortOf(ws, strongId), goTarget(ws, strongId),
                where(ws, weakId), escortOf(ws, weakId));
    }
}
