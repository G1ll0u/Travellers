package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.spawn.TravellerVillageRegistry;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

import static net.minecraft.command.CommandBase.getListOfStringsMatchingLastWord;

/**
 * `/travellersdebug reset …`: removes units for good, loaded and virtual, with the same bookkeeping a departure needs
 * (beds, pending bed respawns, camp slots, village registry, mounts) but none of a death's side effects (no drops, no
 * notice, no bed respawn).
 */
public class ResetDebugCommand implements DebugSubcommand {

    private static final String USAGE = "all|travellers|raiders|henchmen|recruitedhenchmen all|recruitedhenchmen <player>";

    /** What a unit is, for matching: its kind, whether a player hired it, and who. */
    private static final class Subject {
        final String kind;
        final boolean hired;
        @Nullable final UUID owner;

        Subject(String kind, boolean hired, @Nullable UUID owner) {
            this.kind = kind;
            this.hired = hired;
            this.owner = owner;
        }

        static Subject of(EntityTraveller t) {
            if (t instanceof EntityTravellerRaider) return new Subject("RAIDER", false, null);
            if (t instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) t;
                return new Subject("WORKER", w.isHiredFollower(), w.getFollowOwnerId());
            }
            return new Subject("TRAVELLER", false, null);
        }

        static Subject of(VirtualTravellerRecord r) {
            return new Subject(r.entityClass, r.isHiredFollower, r.followOwnerUUID);
        }
    }

    private static final class Tally {
        int loaded;
        int virtual;
        int respawnsCancelled;
        final Set<UUID> hiredOwners = new HashSet<>();
    }

    @Override
    public String name() {
        return "reset";
    }

    @Override
    public String description() {
        return "Remove units for good (all, travellers, raiders, henchmen, or recruited henchmen)";
    }

    @Override
    public String usage() {
        return USAGE;
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) return false;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "all":
                if (args.length != 2) return false;
                return run(server, sender, "units (recruited henchmen untouched)", s -> !s.hired, null, false);
            case "travellers":
                if (args.length != 2) return false;
                return run(server, sender, "travellers", s -> s.kind.equals("TRAVELLER"), null, false);
            case "raiders":
                if (args.length != 2) return false;
                return run(server, sender, "raiders", s -> s.kind.equals("RAIDER"), null, false);
            case "henchmen":
                if (args.length != 2) return false;
                return run(server, sender, "henchmen (recruited ones untouched)", s -> s.kind.equals("WORKER") && !s.hired, null, false);
            case "recruitedhenchmen":
                if (args.length != 3) return false;
                if (args[2].equalsIgnoreCase("all")) {
                    return run(server, sender, "recruited henchmen of all players", s -> s.hired, null, true);
                }
                UUID owner = resolveOwner(server, args[2]);
                return run(server, sender, "recruited henchmen of " + args[2],
                        s -> s.hired && owner.equals(s.owner), owner, true);
            default:
                return false;
        }
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "all", "travellers", "raiders", "henchmen", "recruitedhenchmen");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("recruitedhenchmen")) {
            List<String> options = new ArrayList<>();
            options.add("all");
            if (sender.getServer() != null) Collections.addAll(options, sender.getServer().getOnlinePlayerNames());
            return getListOfStringsMatchingLastWord(args, options);
        }
        return Collections.emptyList();
    }

    private static UUID resolveOwner(MinecraftServer server, String name) throws CommandException {
        EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(name);
        if (online != null) return online.getUniqueID();
        GameProfile known = server.getPlayerProfileCache().getGameProfileForUsername(name);
        if (known == null) throw new CommandException("Unknown player '" + name + "'.");
        return known.getId();
    }

    private boolean run(MinecraftServer server, ICommandSender sender, String label, Predicate<Subject> match,
                        @Nullable UUID ownerFilter, boolean cancelRespawns) {
        Tally tally = new Tally();
        for (WorldServer ws : server.worlds) {
            wipeLoaded(ws, match, tally);
            wipeVirtual(ws, match, tally);
            if (cancelRespawns) cancelPendingRespawns(ws, ownerFilter, tally);
        }
        // Their Hub list would otherwise keep showing henchmen that are gone.
        for (UUID owner : tally.hiredOwners) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(owner);
            if (player != null) WorkerGroupService.sendHub(player, true);
        }
        String respawns = tally.respawnsCancelled > 0 ? ", " + tally.respawnsCancelled + " pending bed respawns cancelled" : "";
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Removed " + label + ": "
                + TextFormatting.YELLOW + tally.virtual + TextFormatting.GREEN + " offscreen, "
                + TextFormatting.YELLOW + tally.loaded + TextFormatting.GREEN + " in the world" + respawns + "."));
        return true;
    }

    private static void wipeLoaded(WorldServer ws, Predicate<Subject> match, Tally tally) {
        for (Entity e : new ArrayList<>(ws.loadedEntityList)) {
            if (!(e instanceof EntityTraveller) || e.isDead) continue;
            EntityTraveller t = (EntityTraveller) e;
            Subject s = Subject.of(t);
            if (!match.test(s)) continue;
            // A recruit's horse goes on living as an ordinary horse; a wild unit's horse goes with it.
            if (s.hired) TravellerMountSupport.releaseMountOnRiderDeath(t);
            else TravellerMountSupport.detachAndKillMountForDebugWipe(t);
            // setDead, never onDeath: no drops, no death notice, no bed respawn.
            t.setDead();
            forget(ws, t.getUniqueID(), s, t instanceof EntityTravellerWorker ? ((EntityTravellerWorker) t).getHomeVillage() : null, tally);
            tally.loaded++;
        }
    }

    private static void wipeVirtual(WorldServer ws, Predicate<Subject> match, Tally tally) {
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        List<VirtualTravellerRecord> hits = new ArrayList<>();
        for (VirtualTravellerRecord r : mgr.getAllRecords()) {
            if (match.test(Subject.of(r))) hits.add(r);
        }
        for (VirtualTravellerRecord r : hits) {
            Subject s = Subject.of(r);
            mgr.removeRecord(ws, r.uuid);
            forget(ws, r.uuid, s, r.homeVillage, tally);
            tally.virtual++;
        }
    }

    /** Frees every place that still names the unit. */
    private static void forget(WorldServer ws, UUID id, Subject s, @Nullable net.minecraft.util.math.BlockPos homeVillage, Tally tally) {
        CampsiteManager.get(ws).releaseOccupant(id);
        if (s.kind.equals("WORKER")) {
            if (s.hired) {
                for (WorldServer w : ws.getMinecraftServer().worlds) PlayerZoneManager.get(w).unassignWorker(id);
                if (s.owner != null) tally.hiredOwners.add(s.owner);
            } else if (homeVillage != null) {
                TravellerVillageRegistry.get(ws).clearWorkerFor(homeVillage, id);
            }
        }
    }

    /** Dead recruits waiting to come back at their bed are henchmen too. */
    private static void cancelPendingRespawns(WorldServer ws, @Nullable UUID owner, Tally tally) {
        PlayerZoneManager mgr = PlayerZoneManager.get(ws);
        List<UUID> pending = new ArrayList<>();
        for (PlayerZoneManager.PendingRespawn pr : mgr.getPendingRespawnsView().values()) {
            if (owner == null || owner.equals(pr.ownerUUID)) pending.add(pr.workerUUID);
        }
        for (UUID id : pending) {
            mgr.unassignWorker(id);
            tally.respawnsCancelled++;
        }
    }
}
