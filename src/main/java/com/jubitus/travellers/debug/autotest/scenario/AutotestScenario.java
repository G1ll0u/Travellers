package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.debug.autotest.AutotestInvariants;
import com.jubitus.travellers.debug.trace.TraceSession;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One scripted check of a behaviour. {@link #begin} sets the situation up, {@link #poll} judges it each tick,
 * {@link #cleanup} removes every unit the scenario created. Judge outcomes only (arrived, equipped, regrouped),
 * never exact paths: AI randomness makes paths differ between runs.
 */
public abstract class AutotestScenario {

    private static final GameProfile OWNER = new GameProfile(
            UUID.nameUUIDFromBytes("travellers-autotest-owner".getBytes()), "[TravellersAutotest]");

    private final List<UUID> spawned = new ArrayList<>();
    private final List<String> traceFiles = new ArrayList<>();

    public abstract String name();

    public abstract int budgetSeconds();

    /** Sets the situation up. Null when it runs; otherwise a SKIP (nothing to test here) or FAIL outcome. */
    @Nullable
    public abstract Outcome begin(AutotestArena arena, int attempt);

    public abstract Outcome poll(WorldServer ws, long ageTicks);

    /** One line on where things stand, appended to a failure. */
    public String describeState(WorldServer ws) {
        return "";
    }

    /** False when the situation cannot be rebuilt a second time (the second half of a restart scenario). */
    public boolean retriesOnFailure() {
        return true;
    }

    /** True when the units must survive a passing run (the first half of a restart scenario). */
    public boolean keepsUnitsOnPass() {
        return false;
    }

    /** Takes over a unit created by an earlier session, so cleanup removes it. */
    protected final void adopt(UUID id) {
        spawned.add(id);
    }

    /** Trace files of the units involved, for the failure report. */
    public final List<String> traceFiles() {
        return traceFiles;
    }

    public final void cleanup(WorldServer ws) {
        VirtualTravellerManager virtual = VirtualTravellerManager.get(ws);
        for (UUID id : spawned) {
            // The invariants pass must not read a removed hired worker as "vanished without dying".
            AutotestInvariants.onVirtualDeath(id);
            Entity e = ws.getEntityFromUuid(id);
            if (e != null) e.setDead();
            virtual.removeRecord(ws, id);
        }
        spawned.clear();
    }

    /** A worker hired by the test account at {@code pos}, traced; null when the spawn is refused. */
    @Nullable
    protected final EntityTravellerWorker spawnHiredWorker(WorldServer ws, BlockPos pos) {
        return spawnHiredWorker(ws, pos, OWNER);
    }

    /** Same, hired by a real account's identity (for places that check who owns the worker). */
    @Nullable
    protected final EntityTravellerWorker spawnHiredWorker(WorldServer ws, BlockPos pos, UUID ownerId) {
        return spawnHiredWorker(ws, pos, new GameProfile(ownerId, "[TravellersAutotest]"));
    }

    @Nullable
    private EntityTravellerWorker spawnHiredWorker(WorldServer ws, BlockPos pos, GameProfile profile) {
        FakePlayer owner = FakePlayerFactory.get(ws, profile);
        EntityTravellerWorker w = new EntityTravellerWorker(ws);
        w.setLocationAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        w.onInitialSpawn(ws.getDifficultyForLocation(pos), null);
        w.hireAsFollower(owner);
        if (!ws.spawnEntity(w)) return null;
        track(w);
        return w;
    }

    protected final void track(EntityTraveller unit) {
        spawned.add(unit.getUniqueID());
        TraceSession trace = TravellerTracer.start(unit, TravellerTracer.getDefaultChannels());
        if (trace != null) traceFiles.add(trace.getFile().getName());
    }

    @Nullable
    protected static EntityTraveller loaded(WorldServer ws, UUID id) {
        Entity e = ws.getEntityFromUuid(id);
        return e instanceof EntityTraveller && !e.isDead ? (EntityTraveller) e : null;
    }

    @Nullable
    protected static VirtualTravellerRecord record(WorldServer ws, UUID id) {
        return VirtualTravellerManager.get(ws).getRecord(id);
    }

    /** Where a unit is right now, loaded or virtual; null when it exists as neither. */
    @Nullable
    protected static double[] position(WorldServer ws, UUID id) {
        EntityTraveller e = loaded(ws, id);
        if (e != null) return new double[]{e.posX, e.posY, e.posZ};
        VirtualTravellerRecord r = record(ws, id);
        return r == null ? null : new double[]{r.posX, r.posY, r.posZ};
    }

    protected static double horizontalDistance(double[] pos, BlockPos target) {
        double dx = pos[0] - (target.getX() + 0.5D), dz = pos[2] - (target.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    protected static String where(WorldServer ws, UUID id) {
        if (loaded(ws, id) != null) return "loaded";
        return record(ws, id) != null ? "virtual" : "gone";
    }
}
