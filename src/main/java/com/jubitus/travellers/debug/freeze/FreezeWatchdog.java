package com.jubitus.travellers.debug.freeze;

import com.jubitus.travellers.follow.EntityAIFollowLeader;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Always-on detector of mass freezes: many loaded units that should be moving but are not, or many
 * loaded units that no longer tick at all. Writes one snapshot per episode to
 * {@code logs/travellers-profile/freeze_*.txt}. A unit that is legitimately idle (no order, resting,
 * archer at range, hired worker in combat whose attitude decides) is never counted. Server thread only.
 */
public final class FreezeWatchdog {

    private static final int SAMPLE_TICKS = 20;
    private static final int MAX_SAMPLE_GAP_TICKS = 100;
    private static final int WINDOW_TICKS = 100;
    private static final double FROZEN_SECONDS = 8.0D;
    private static final double STILL_BLOCKS_PER_SECOND = 0.3D;
    private static final double TELEPORT_BLOCKS_PER_SECOND = 10.0D;
    private static final double FOLLOW_MIN_DISTANCE = 25.0D;
    private static final double TRAVEL_MIN_DISTANCE = 24.0D;
    private static final double COMBAT_MIN_DISTANCE = 6.0D;
    private static final double COMBAT_MAX_DISTANCE = 48.0D;
    private static final int MIN_UNITS = 20;
    private static final double TRIGGER_RATIO = 0.5D;
    private static final double RECOVER_RATIO = 0.15D;
    private static final long COOLDOWN_TICKS = 6000L;
    private static final int MAX_DETAILS = 12;

    private static final Map<EntityTraveller, State> STATES = new WeakHashMap<>();
    private static final List<String> frozenDetails = new ArrayList<>();
    private static final Map<String, Integer> eligibleByCategory = new HashMap<>();
    private static final Map<String, Integer> frozenByCategory = new HashMap<>();
    private static final Map<String, Integer> fingerprints = new HashMap<>();

    private static long windowStart = Long.MIN_VALUE;
    private static int windowEligible;
    private static int windowFrozen;

    @Nullable
    private static File activeFile;
    private static long activeSince;
    private static long cooldownUntil;

    private FreezeWatchdog() {
    }

    private static final class State {
        long lastSampleTick = Long.MIN_VALUE;
        long lastTickedAt = Long.MIN_VALUE;
        double lastX;
        double lastZ;
        double frozenSeconds;
    }

    /** Called from each unit's own tick; samples it once per second. */
    public static void tick(EntityTraveller e) {
        if (e.world.isRemote) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        long now = server.getTickCounter();
        State st = STATES.computeIfAbsent(e, k -> new State());
        st.lastTickedAt = now;
        long dt = now - st.lastSampleTick;
        if (dt < SAMPLE_TICKS) return;

        double dx = e.posX - st.lastX;
        double dz = e.posZ - st.lastZ;
        double disp = Math.sqrt(dx * dx + dz * dz);
        boolean firstOrGap = st.lastSampleTick == Long.MIN_VALUE || dt > MAX_SAMPLE_GAP_TICKS;
        st.lastSampleTick = now;
        st.lastX = e.posX;
        st.lastZ = e.posZ;
        if (firstOrGap) {
            st.frozenSeconds = 0.0D;
            return;
        }

        String category = expectedMovement(e);
        if (category == null) {
            st.frozenSeconds = 0.0D;
            return;
        }
        double secs = dt / 20.0D;
        if (disp >= STILL_BLOCKS_PER_SECOND * secs || disp > TELEPORT_BLOCKS_PER_SECOND * secs) {
            st.frozenSeconds = 0.0D;
        } else {
            st.frozenSeconds += secs;
        }
        windowEligible++;
        eligibleByCategory.merge(category, 1, Integer::sum);
        if (st.frozenSeconds < FROZEN_SECONDS) return;

        windowFrozen++;
        frozenByCategory.merge(category, 1, Integer::sum);
        fingerprints.merge(fingerprint(e, category), 1, Integer::sum);
        if (frozenDetails.size() < MAX_DETAILS) frozenDetails.add(describe(e, category, st.frozenSeconds));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        long now = server.getTickCounter();
        if (windowStart == Long.MIN_VALUE) windowStart = now;
        if (now - windowStart < WINDOW_TICKS) return;
        endWindow(server, now);
        windowStart = now;
    }

    public static void reset() {
        STATES.clear();
        clearWindow();
        activeFile = null;
        windowStart = Long.MIN_VALUE;
        cooldownUntil = 0L;
    }

    /** Why this unit should be moving right now, or null when standing still is fine. */
    @Nullable
    private static String expectedMovement(EntityTraveller e) {
        if (e.isDead || e.sleep.isSleeping()) return null;
        if (e.isRiding() && e.getRidingEntity() instanceof EntityTravellerBoat) return null;

        EntityLivingBase target = e.getAttackTarget();
        if (target != null && !target.isDead) {
            // A hired worker's attitude (hold/defend) decides whether it chases; an archer shoots from range.
            if (e instanceof EntityTravellerWorker || e.hasBowAnywherePublic()) return null;
            double d = e.getDistance(target);
            return d > COMBAT_MIN_DISTANCE && d < COMBAT_MAX_DISTANCE ? "combat" : null;
        }
        if (e.isInCombat() || e.isDispersedInVillage()) return null;

        if (e.isFollowOrderActive()) {
            if (e instanceof EntityTravellerWorker && !e.isHiredFollower()) return null;
            if (e.isCatchupTravel() || e.isJobActive()) return null;
            EntityLivingBase leader = e.getEffectiveFollowTarget();
            if (leader == null || leader.isDead) return null;
            return horizontal(e, leader.posX, leader.posZ) > FOLLOW_MIN_DISTANCE ? "follow" : null;
        }

        BlockPos village = e.getTravelCommon().getTargetVillage();
        if (village == null || !e.getTravelCommon().isReadyToDepart()) return null;
        if (e.getTravelCommon().getDepartCooldownTicks() > 0) return null;
        return horizontal(e, village.getX() + 0.5D, village.getZ() + 0.5D) > TRAVEL_MIN_DISTANCE ? "travel" : null;
    }

    private static double horizontal(Entity e, double x, double z) {
        double dx = e.posX - x;
        double dz = e.posZ - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String fingerprint(EntityTraveller e, String category) {
        EntityAIBase task = e.getRunningMovementTask();
        return String.format(Locale.ROOT, "%s | ai=%s | %s | %s", category,
                task == null ? "none" : task.getClass().getSimpleName(),
                e.getNavigator().noPath() ? "no path" : "has path",
                e.moveForward > 0.02F ? "steering" : "not steering");
    }

    private static String describe(EntityTraveller e, String category, double frozenSeconds) {
        EntityAIBase task = e.getRunningMovementTask();
        EntityLivingBase target = e.getAttackTarget();
        String followOff = "";
        for (EntityAITasks.EntityAITaskEntry entry : e.tasks.taskEntries) {
            if (entry.action instanceof EntityAIFollowLeader && !entry.using) {
                followOff = " followOff=" + ((EntityAIFollowLeader) entry.action).whyNotRunning();
            }
        }
        return String.format(Locale.ROOT,
                "%s [%s] %s at %d %d %d | %s | frozen %.0fs | ai=%s | %s | moveForward=%.2f | target=%s%s",
                e.getName(), e.getClass().getSimpleName(), e.isMountedOnBondedMount() ? "mounted" : "on foot",
                (int) e.posX, (int) e.posY, (int) e.posZ, category, frozenSeconds,
                task == null ? "none" : task.getClass().getSimpleName(),
                e.getNavigator().noPath() ? "no path" : "has path", e.moveForward,
                target == null ? "-" : target.getClass().getSimpleName(), followOff);
    }

    private static void endWindow(MinecraftServer server, long now) {
        int loaded = 0;
        int ticked = 0;
        List<String> notTicking = new ArrayList<>();
        for (World world : server.worlds) {
            for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
                if (!(entity instanceof EntityTraveller) || entity.isDead) continue;
                loaded++;
                State st = STATES.get(entity);
                if (st != null && st.lastTickedAt >= windowStart) {
                    ticked++;
                } else if (notTicking.size() < MAX_DETAILS) {
                    notTicking.add(String.format(Locale.ROOT, "%s at %d %d %d | ticksExisted=%d | chunkLoaded=%b",
                            entity.getName(), (int) entity.posX, (int) entity.posY, (int) entity.posZ,
                            entity.ticksExisted, world.isBlockLoaded(entity.getPosition())));
                }
            }
        }

        boolean frozenTrigger = windowEligible >= MIN_UNITS && windowFrozen >= TRIGGER_RATIO * windowEligible;
        boolean tickTrigger = loaded >= MIN_UNITS && ticked < TRIGGER_RATIO * loaded;
        boolean frozenRecovered = windowEligible < MIN_UNITS || windowFrozen < RECOVER_RATIO * windowEligible;
        boolean tickRecovered = loaded < MIN_UNITS || ticked >= (1.0D - RECOVER_RATIO) * loaded;

        try {
            if (activeFile == null) {
                if ((frozenTrigger || tickTrigger) && now >= cooldownUntil) {
                    activeFile = newFile(server);
                    activeSince = now;
                    String reason = frozenTrigger ? "FROZEN (should move, does not)" : "NOT TICKING (loaded but never updated)";
                    append(activeFile, header(server, now, reason, loaded, ticked, notTicking));
                    FMLLog.log(Level.WARN, "[Travellers/Freeze] %s, snapshot in %s", reason, activeFile.getName());
                }
            } else if (frozenRecovered && tickRecovered) {
                append(activeFile, String.format(Locale.ROOT, "%nRECOVERED after %ds%n", (now - activeSince) / 20L));
                activeFile = null;
                cooldownUntil = now + COOLDOWN_TICKS;
            } else {
                append(activeFile, String.format(Locale.ROOT,
                        "+%ds: loaded %d, ticked %d | should move %d, frozen %d%n",
                        (now - activeSince) / 20L, loaded, ticked, windowEligible, windowFrozen));
            }
        } catch (IOException ex) {
            FMLLog.log(Level.WARN, "[Travellers/Freeze] cannot write snapshot: %s", ex.toString());
            activeFile = null;
        }
        clearWindow();
    }

    private static void clearWindow() {
        windowEligible = 0;
        windowFrozen = 0;
        frozenDetails.clear();
        eligibleByCategory.clear();
        frozenByCategory.clear();
        fingerprints.clear();
    }

    private static String header(MinecraftServer server, long now, String reason, int loaded, int ticked,
                                 List<String> notTicking) {
        long sum = 0L;
        for (long t : server.tickTimeArray) sum += t;
        double mspt = sum / (double) server.tickTimeArray.length / 1.0E6D;
        StringBuilder sb = new StringBuilder();
        sb.append("=== Travellers freeze snapshot ===\n");
        sb.append("Trigger: ").append(reason).append('\n');
        sb.append(String.format(Locale.ROOT, "Server tick %d | average %.1f ms per tick%n", now, mspt));
        sb.append(String.format(Locale.ROOT, "Loaded units %d | ticked during the last 5 s %d%n", loaded, ticked));
        sb.append(String.format(Locale.ROOT, "Units that should move %d | still for 8 s or more %d%n",
                windowEligible, windowFrozen));
        sb.append("\nBy reason to move (frozen / should move):\n");
        for (Map.Entry<String, Integer> en : eligibleByCategory.entrySet()) {
            sb.append(String.format(Locale.ROOT, "  %-8s %d / %d%n", en.getKey(),
                    frozenByCategory.getOrDefault(en.getKey(), 0), en.getValue()));
        }
        sb.append("\nFrozen units by state:\n");
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(fingerprints.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> en : sorted) {
            sb.append(String.format(Locale.ROOT, "  %4d  %s%n", en.getValue(), en.getKey()));
        }
        sb.append("\nSample of frozen units:\n");
        for (String d : frozenDetails) sb.append("  ").append(d).append('\n');
        if (!notTicking.isEmpty()) {
            sb.append("\nSample of loaded units that did not tick:\n");
            for (String d : notTicking) sb.append("  ").append(d).append('\n');
        }
        sb.append("\nTimeline:\n");
        return sb.toString();
    }

    private static File newFile(MinecraftServer server) throws IOException {
        File dir = new File(server.getDataDirectory(), "logs/travellers-profile");
        Files.createDirectories(dir.toPath());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        return new File(dir, "freeze_" + stamp + ".txt");
    }

    private static void append(File file, String text) throws IOException {
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(text);
        }
    }
}
