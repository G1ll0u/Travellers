package com.jubitus.travellers.raid;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Decides, once a second and per player, when a raid starts and when it is over. */
public final class RaidScheduler {

    private static final int CHECK_INTERVAL_TICKS = 20;
    /** A raid that is not finished by then is over, whatever its raiders are doing. */
    private static final long MAX_RAID_TICKS = 20L * 60 * 20;

    /** A raid that could not be placed is tried again after this long. */
    private static final long RETRY_TICKS = 2400L;
    /** Raiders are re-aimed every this many checks (seconds). */
    private static final int REAIM_EVERY_CHECKS = 5;
    /** The player must move this far from the raiders' stop before they are re-aimed. */
    private static final int REAIM_DISTANCE = 16;

    /** Players already examined since this server started (see {@link #tickPlayer}). */
    private static final Set<UUID> SEEN_THIS_SESSION = new HashSet<>();

    private RaidScheduler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        WorldServer overworld = server.getWorld(0);
        if (overworld == null || overworld.getTotalWorldTime() % CHECK_INTERVAL_TICKS != 0) return;

        RaidWorldData data = RaidWorldData.get(server);
        // INVARIANT: a running raid is maintained whether or not raids are enabled and whether or not
        // its player is online; "enabled" only gates the scheduling of new ones.
        long now = overworld.getTotalWorldTime();
        for (Map.Entry<UUID, RaidWorldData.PlayerRaidState> entry : data.allStates()) {
            if (entry.getValue().activeRaidId == null) continue;
            tickActiveRaid(overworld, data, server.getPlayerList().getPlayerByUUID(entry.getKey()),
                    entry.getValue(), now);
        }
        if (!TravellersConfig.RAIDS.enabled) return;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            tickPlayer(overworld, data, player);
        }
    }

    private static void tickPlayer(WorldServer overworld, RaidWorldData data, EntityPlayerMP player) {
        if (player.dimension != 0 || player.isCreative() || player.isSpectator()) return;
        if (TravellersDerivedConfig.isPeacefulBlocked(overworld)) return;

        long now = overworld.getTotalWorldTime();
        UUID id = player.getUniqueID();
        RaidWorldData.PlayerRaidState s = data.find(id);
        if (s == null) {
            s = data.create(id);
            s.firstSeenTick = now;
            reschedule(data, s, now, overworld.rand);
        } else if (SEEN_THIS_SESSION.add(id) && s.activeRaidId == null && s.earliestTick < now) {
            // The player was away when it fell due: never pay the debt as a raid on login.
            reschedule(data, s, now, overworld.rand);
        }

        if (s.activeRaidId != null) return;

        long graceEnd = s.firstSeenTick + Math.round(TravellersConfig.RAIDS.graceDays * RaidSchedule.DAY_TICKS);
        if (now < graceEnd) return;
        if (!RaidSchedule.isDue(now, s.earliestTick, s.slot, s.slotOffset, overworld.getWorldTime())) return;

        startRaid(overworld, data, player, s, now);
    }

    /** Starts a raid for this player right now, scheduled or not. @return false when nothing could be placed. */
    static boolean startRaid(WorldServer overworld, RaidWorldData data, EntityPlayerMP player,
                             RaidWorldData.PlayerRaidState s, long now) {
        UUID raidId = UUID.randomUUID();
        s.activeRaidId = raidId;
        s.activeSinceTick = now;
        int raiders = RaidLauncher.launch(overworld, player, data, raidId);
        if (raiders == 0) {
            s.activeRaidId = null;
            s.earliestTick = now + RETRY_TICKS;
            data.markDirty();
            return false;
        }
        data.markDirty();
        FMLLog.log(Level.INFO, "[Travellers/Raid] raid %s on %s: %d raiders", raidId, player.getName(), raiders);
        return true;
    }

    /** Ends the raid when its raiders are all gone or its time is up; otherwise keeps them aimed at the player. */
    private static void tickActiveRaid(WorldServer ws, RaidWorldData data, @Nullable EntityPlayerMP player,
                                       RaidWorldData.PlayerRaidState s, long now) {
        UUID raidId = s.activeRaidId;
        boolean aim = player != null && player.dimension == 0 && !player.isDead
                && (now / CHECK_INTERVAL_TICKS) % REAIM_EVERY_CHECKS == 0;
        BlockPos goal = aim ? player.getPosition() : null;
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        int alive = 0;
        for (UUID id : data.membersOf(raidId)) {
            Entity e = ws.getEntityFromUuid(id);
            if (e instanceof EntityTraveller) {
                if (!e.isEntityAlive()) continue;
                alive++;
                if (aim) reaimLoaded((EntityTraveller) e, goal);
                continue;
            }
            VirtualTravellerRecord r = mgr.getRecord(id);
            if (r == null) continue;
            alive++;
            if (aim && r.groupLeaderUUID == null && farFrom(r.currentTripStop(), goal)) {
                mgr.retargetRaidLeader(id, goal);
            }
        }
        if (alive == 0 || now - s.activeSinceTick > MAX_RAID_TICKS) {
            data.clearRaid(raidId);
            s.activeRaidId = null;
            reschedule(data, s, now, ws.rand);
        }
    }

    private static void reaimLoaded(EntityTraveller t, BlockPos goal) {
        if (t.getGroupLeaderUUID() != null) return;
        if (farFrom(t.getTravelCommon().getTargetVillage(), goal)) t.getTravelCommon().routeTo(goal);
    }

    private static boolean farFrom(BlockPos stop, BlockPos goal) {
        if (stop == null) return true;
        long dx = stop.getX() - goal.getX();
        long dz = stop.getZ() - goal.getZ();
        return dx * dx + dz * dz > REAIM_DISTANCE * REAIM_DISTANCE;
    }

    private static void reschedule(RaidWorldData data, RaidWorldData.PlayerRaidState s, long now,
                                   java.util.Random rng) {
        s.earliestTick = RaidSchedule.rollEarliestTick(now, TravellersConfig.RAIDS.averageIntervalDays, rng);
        s.slot = RaidSchedule.rollSlot(TravellersConfig.RAIDS.nightWeight, TravellersConfig.RAIDS.dayWeight, rng);
        s.slotOffset = RaidSchedule.rollOffset(s.slot, rng);
        data.markDirty();
    }

    // --- Debug entry points (/travellersdebug raid) ---

    /** Starts a raid on this player now, ignoring the timer, the grace period and the enabled switch. */
    public static String startNow(EntityPlayerMP player) {
        WorldServer overworld = player.getServer().getWorld(0);
        if (player.dimension != 0) return "Raids only target players in the Overworld.";
        RaidWorldData data = RaidWorldData.get(player.getServer());
        RaidWorldData.PlayerRaidState s = stateOf(overworld, data, player);
        if (s.activeRaidId != null) return "A raid is already running on you.";
        long now = overworld.getTotalWorldTime();
        if (!startRaid(overworld, data, player, s, now)) {
            return "No raid could start: no allowed village culture in the world, or no free spot at "
                    + "the spawn distance.";
        }
        String warn = TravellersDerivedConfig.isPeacefulBlocked(overworld)
                ? " (Peaceful difficulty: raiders will not attack.)" : "";
        return "Raid started: " + data.membersOf(s.activeRaidId).size() + " raiders." + warn;
    }

    /** Ends the player's raid at once; its raiders go back to ordinary life. */
    public static String endNow(EntityPlayerMP player) {
        WorldServer overworld = player.getServer().getWorld(0);
        RaidWorldData data = RaidWorldData.get(player.getServer());
        RaidWorldData.PlayerRaidState s = data.find(player.getUniqueID());
        if (s == null || s.activeRaidId == null) return "No raid is running on you.";
        data.clearRaid(s.activeRaidId);
        s.activeRaidId = null;
        reschedule(data, s, overworld.getTotalWorldTime(), overworld.rand);
        return "Raid ended.";
    }

    public static String describe(EntityPlayerMP player) {
        WorldServer overworld = player.getServer().getWorld(0);
        RaidWorldData data = RaidWorldData.get(player.getServer());
        RaidWorldData.PlayerRaidState s = data.find(player.getUniqueID());
        if (s == null) return "Raids: nothing scheduled for you yet (enabled=" + TravellersConfig.RAIDS.enabled + ").";
        long now = overworld.getTotalWorldTime();
        if (s.activeRaidId != null) {
            return "Raid running for " + (now - s.activeSinceTick) / 20 + " s, "
                    + data.membersOf(s.activeRaidId).size() + " raiders.\n" + membersReport(overworld, data, player, s);
        }
        long graceEnd = s.firstSeenTick + Math.round(TravellersConfig.RAIDS.graceDays * RaidSchedule.DAY_TICKS);
        return "Next raid: not before " + Math.max(0L, s.earliestTick - now) / 20 + " s, in the "
                + s.slot.name().toLowerCase() + " slot, grace ends in " + Math.max(0L, graceEnd - now) / 20
                + " s (enabled=" + TravellersConfig.RAIDS.enabled + ").";
    }

    private static RaidWorldData.PlayerRaidState stateOf(WorldServer overworld, RaidWorldData data,
                                                         EntityPlayerMP player) {
        RaidWorldData.PlayerRaidState s = data.find(player.getUniqueID());
        if (s != null) return s;
        s = data.create(player.getUniqueID());
        s.firstSeenTick = overworld.getTotalWorldTime();
        reschedule(data, s, s.firstSeenTick, overworld.rand);
        return s;
    }

    /** Where the raiders are and what the nearest loaded and nearest virtual one are doing. */
    private static String membersReport(WorldServer ws, RaidWorldData data, EntityPlayerMP player,
                                        RaidWorldData.PlayerRaidState s) {
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        int loaded = 0, virtual = 0, gone = 0;
        EntityTraveller nearestLoaded = null;
        VirtualTravellerRecord nearestVirtual = null;
        double bestLoaded = Double.MAX_VALUE, bestVirtual = Double.MAX_VALUE, bestLeader = Double.MAX_VALUE;
        EntityTraveller nearestLeader = null;
        for (UUID id : data.membersOf(s.activeRaidId)) {
            Entity e = ws.getEntityFromUuid(id);
            if (e instanceof EntityTraveller && e.isEntityAlive()) {
                loaded++;
                double d = e.getDistanceSq(player);
                if (d < bestLoaded) {
                    bestLoaded = d;
                    nearestLoaded = (EntityTraveller) e;
                }
                if (((EntityTraveller) e).getGroupLeaderUUID() == null && d < bestLeader) {
                    bestLeader = d;
                    nearestLeader = (EntityTraveller) e;
                }
                continue;
            }
            VirtualTravellerRecord r = mgr.getRecord(id);
            if (r == null) {
                gone++;
                continue;
            }
            virtual++;
            double dx = r.posX - player.posX, dz = r.posZ - player.posZ, dy = r.posY - player.posY;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestVirtual) {
                bestVirtual = d;
                nearestVirtual = r;
            }
        }
        StringBuilder sb = new StringBuilder("loaded=" + loaded + " virtual=" + virtual + " dead=" + gone);
        if (nearestLoaded != null) {
            EntityTraveller t = nearestLoaded;
            sb.append("\nnearest loaded: ").append((int) Math.sqrt(bestLoaded)).append(" blocks (dy=")
                    .append((int) (t.posY - player.posY)).append(")")
                    .append(" leader=").append(t.getGroupLeaderUUID() != null)
                    .append(" attackTarget=").append(t.getAttackTarget() == null ? "none"
                            : t.getAttackTarget().getName())
                    .append(" inCombat=").append(t.isInCombat())
                    .append(" travelTarget=").append(t.getTravelCommon().getTargetVillage())
                    .append(" atStop=").append(t.isAtTargetStop());
        }
        if (nearestLeader != null) {
            EntityTraveller t = nearestLeader;
            sb.append("\nnearest LEADER: ").append((int) Math.sqrt(bestLeader)).append(" blocks (dy=")
                    .append((int) (t.posY - player.posY)).append(")")
                    .append(" attackTarget=").append(t.getAttackTarget() == null ? "none"
                            : t.getAttackTarget().getName())
                    .append(" inCombat=").append(t.isInCombat())
                    .append(" travelTarget=").append(t.getTravelCommon().getTargetVillage())
                    .append(" waypoint=").append(t.getTravelCommon().getCurrentWaypoint())
                    .append(" ready=").append(t.getTravelCommon().isReadyToDepart())
                    .append(" cooldown=").append(t.getTravelCommon().getDepartCooldownTicks())
                    .append(" pause=").append(t.getTravelCommon().getPauseTicks())
                    .append(" atStop=").append(t.isAtTargetStop())
                    .append(" visiting=").append(t.isVisitingVillage())
                    .append(" mounted=").append(t.getRidingEntity() != null)
                    .append(" frozenByVanilla=").append(VirtualTravellerManager.isFrozenByVanillaAt(ws, t.posX, t.posZ))
                    .append(" tracked=").append(t.isTrackedByAnyPlayer())
                    .append(" ticksExisted=").append(t.ticksExisted);
            int pcx = net.minecraft.util.math.MathHelper.floor(player.posX) >> 4;
            int pcz = net.minecraft.util.math.MathHelper.floor(player.posZ) >> 4;
            int east = 0;
            while (east < 32 && com.jubitus.travellers.ground.chunk.LoadedChunks
                    .isChunkLoaded(ws, pcx + east + 1, pcz)) east++;
            sb.append("\nloaded chunks east of you: ").append(east).append(" (server view distance ")
                    .append(ws.getMinecraftServer().getPlayerList().getViewDistance()).append(")");
        }
        if (nearestVirtual != null) {
            VirtualTravellerRecord r = nearestVirtual;
            sb.append("\nnearest virtual: ").append((int) Math.sqrt(bestVirtual)).append(" blocks")
                    .append(" follower=").append(r.groupLeaderUUID != null)
                    .append(" goal=").append(r.goal)
                    .append(" stop=").append(r.currentTripStop())
                    .append(" itineraryBuilt=").append(r.currentTripStop() != null
                            && r.itinerary.isFor(r.currentTripStop()))
                    .append(" visit=").append(r.visit != null)
                    .append(" camp=").append(r.campSiteId != null);
        }
        return sb.toString();
    }
}
