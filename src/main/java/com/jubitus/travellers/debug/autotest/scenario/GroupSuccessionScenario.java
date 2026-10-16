package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.debug.autotest.AutotestInvariants;
import com.jubitus.travellers.follow.GroupSuccession;
import com.jubitus.travellers.unit.EntityTraveller;
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
 * A traveller group loses its leader: the best-equipped member takes the lead, the other member follows it, and
 * (when the leader died with a readable trip) the new leader heads for the old leader's destination. Variants: the
 * leader dies loaded, dies offscreen (as in a virtual battle), or vanishes without a death (fallback election).
 */
public final class GroupSuccessionScenario extends AutotestScenario {

    public enum Mode {LOADED, VIRTUAL, VANISHED}

    private static final int RUN_LENGTH = 30;
    private static final long KILL_AFTER_TICKS = 40L;
    /** The new leader must get this much closer to the old leader's destination. */
    private static final double PROGRESS_BLOCKS = 6.0D;

    private final Mode mode;
    private UUID leaderId;
    private UUID strongId;
    private UUID weakId;
    private BlockPos goal;
    private boolean killed;
    private boolean tripChecked;
    private double startDistance;

    public GroupSuccessionScenario(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String name() {
        switch (mode) {
            case VIRTUAL:
                return "group-succession-virtual";
            case VANISHED:
                return "group-succession-vanished";
            default:
                return "group-succession";
        }
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
        tripChecked = false;

        EntityTraveller leader = spawnBare(ws, arena.groundAt(run[0].getX(), run[0].getZ()));
        EntityTraveller strong = spawnBare(ws, arena.groundAt(run[0].getX() + 1, run[0].getZ()));
        EntityTraveller weak = spawnBare(ws, arena.groundAt(run[0].getX() + 2, run[0].getZ()));
        if (leader == null || strong == null || weak == null) return Outcome.fail("a traveller spawn was refused");
        leaderId = leader.getUniqueID();
        strongId = strong.getUniqueID();
        weakId = weak.getUniqueID();

        strong.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        strong.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        strong.setItemStackToSlot(EntityEquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        strong.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        strong.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        strong.setGroupLeaderUUID(leaderId);
        weak.setGroupLeaderUUID(leaderId);
        leader.getTravelCommon().routeTo(goal);

        if (mode == Mode.LOADED) return null;

        // Offscreen variants: the whole group goes virtual, then the leader is removed on the same tick.
        VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
        long now = ws.getTotalWorldTime();
        for (EntityTraveller t : new EntityTraveller[]{leader, strong, weak}) {
            if (!vtm.virtualizeTraveller(ws, t, now)) return Outcome.fail("virtualizing " + t.getUniqueID() + " was refused");
        }
        VirtualTravellerRecord leaderRec = vtm.getRecord(leaderId);
        if (leaderRec == null) return Outcome.fail("the leader has no record after virtualizing");
        // Same order as VirtualBattleManager.killVirtual; VANISHED skips the death hook on purpose.
        if (mode == Mode.VIRTUAL) GroupSuccession.onLeaderDied(ws, leaderRec);
        AutotestInvariants.onVirtualDeath(leaderId);
        vtm.removeRecord(ws, leaderId);
        killed = true;
        return null;
    }

    @Nullable
    private EntityTraveller spawnBare(WorldServer ws, @Nullable BlockPos p) {
        if (p == null) return null;
        EntityTraveller t = new EntityTraveller(ws);
        t.setLocationAndAngles(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 0.0F, 0.0F);
        t.onInitialSpawn(ws.getDifficultyForLocation(p), null);
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) t.setItemStackToSlot(slot, ItemStack.EMPTY);
        for (int i = 0; i < t.inventory.getSlots(); i++) t.inventory.setStackInSlot(i, ItemStack.EMPTY);
        if (!ws.spawnEntity(t)) return null;
        track(t);
        return t;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        if (!killed) {
            if (ageTicks < KILL_AFTER_TICKS) return Outcome.PENDING;
            EntityTraveller leader = loaded(ws, leaderId);
            if (leader == null) return Outcome.fail("the leader left the loaded world before it could be killed");
            leader.attackEntityFrom(DamageSource.OUT_OF_WORLD, 10000.0F);
            killed = true;
            return Outcome.PENDING;
        }
        if (position(ws, strongId) == null) return Outcome.fail("the best-equipped member no longer exists");
        if (position(ws, weakId) == null) return Outcome.fail("the other member no longer exists");
        if (!isLeader(ws, strongId) || !strongId.equals(leaderOf(ws, weakId))) return Outcome.PENDING;

        // The successor inherits the old leader's trip, except in the vanished case (no trip to read).
        if (mode == Mode.VANISHED) return Outcome.PASS;
        if (!tripChecked) {
            BlockPos stop = tripStop(ws, strongId);
            if (!goal.equals(stop)) return Outcome.fail("new leader heads for " + stop + ", the old leader's trip was " + goal);
            tripChecked = true;
            startDistance = horizontalDistance(position(ws, strongId), goal);
        }
        double now = horizontalDistance(position(ws, strongId), goal);
        return startDistance - now >= PROGRESS_BLOCKS || now <= PROGRESS_BLOCKS ? Outcome.PASS : Outcome.PENDING;
    }

    private static boolean isLeader(WorldServer ws, UUID id) {
        return position(ws, id) != null && leaderOf(ws, id) == null;
    }

    @Nullable
    private static UUID leaderOf(WorldServer ws, UUID id) {
        EntityTraveller e = loaded(ws, id);
        if (e != null) return e.getGroupLeaderUUID();
        VirtualTravellerRecord r = record(ws, id);
        return r == null ? null : r.groupLeaderUUID;
    }

    @Nullable
    private static BlockPos tripStop(WorldServer ws, UUID id) {
        EntityTraveller e = loaded(ws, id);
        if (e != null) return e.getTravelCommon().getTargetVillage();
        VirtualTravellerRecord r = record(ws, id);
        return r == null ? null : r.currentTripStop();
    }

    @Override
    public String describeState(WorldServer ws) {
        return String.format(Locale.ROOT, "leader %s killed=%s; strong %s leader=%s trip=%s; weak %s leader=%s",
                where(ws, leaderId), killed, where(ws, strongId), leaderOf(ws, strongId), tripStop(ws, strongId),
                where(ws, weakId), leaderOf(ws, weakId));
    }
}
