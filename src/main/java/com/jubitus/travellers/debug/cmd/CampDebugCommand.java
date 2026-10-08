package com.jubitus.travellers.debug.cmd;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug camp …`. */
public class CampDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "camp";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeCampList(server, sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "here", "recompute", "join", "leave", "reset");
        if (args.length == 3 && args[1].equalsIgnoreCase("reset")) return getListOfStringsMatchingLastWord(args, "all");
        return Collections.emptyList();
    }

    private void executeCampList(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            boolean deleteAll = args.length >= 3 && args[2].equalsIgnoreCase("all");
            executeCampReset(server, sender, deleteAll);
            return;
        }

        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Must be run by a player."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer ws = player.getServerWorld();
        com.jubitus.travellers.camp.CampsiteManager mgr =
                com.jubitus.travellers.camp.CampsiteManager.get(ws);
        net.minecraft.util.math.BlockPos origin = player.getPosition();
        int dim = ws.provider.getDimension();

        if (args.length >= 2 && args[1].equalsIgnoreCase("here")) {
            com.jubitus.travellers.camp.CampsiteRecord rec = mgr.forceCreateAt(ws, origin);
            if (rec == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED
                        + "Could not create a campsite here (bad ground or too close to another)."));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Campsite created at "
                        + rec.center + " with " + (rec.slotsComputed ? rec.slots.size() : "?") + " slots."));
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("recompute")) {
            int n = mgr.recomputeNear(ws, origin, 256);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Recomputed slots for " + n + " nearby campsite(s)."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("join")) {
            com.jubitus.travellers.camp.CampsiteRecord rec = mgr.nearest(dim, origin, 256);
            if (rec == null || !rec.slotsComputed) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "No campsite with slots within 256b."));
                return;
            }
            int count = 1;
            try { if (args.length >= 3) count = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
            String culture = args.length >= 4 ? args[3] : "default";
            String faction = args.length >= 5 ? args[4].toUpperCase(java.util.Locale.ROOT) : "TRAVELLER";
            long now = ws.getTotalWorldTime();
            long depart = now + 20L * 60L * 60L; // ~1h, stays put for testing
            int placed = 0;
            for (int i = 0; i < count; i++) {
                int slot = mgr.claim(rec, java.util.UUID.randomUUID(), faction, culture, now, depart);
                if (slot < 0) break;
                placed++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Claimed " + placed + " slot(s) as "
                    + faction + " (culture=" + culture + ") at " + rec.center
                    + ". Shelters build on the next camp tick if a template is installed."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
            com.jubitus.travellers.camp.CampsiteRecord rec = mgr.nearest(dim, origin, 256);
            if (rec == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "No campsite within 256b."));
                return;
            }
            long now = ws.getTotalWorldTime();
            int n = rec.occupants.size();
            for (com.jubitus.travellers.camp.CampOccupant o : rec.occupants.values()) o.departTick = now;
            mgr.markDirty();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Forced " + n
                    + " occupant(s) to leave; camp tears down on the next camp tick."));
            return;
        }

        java.util.List<com.jubitus.travellers.camp.CampsiteRecord> near = new java.util.ArrayList<>();
        for (com.jubitus.travellers.camp.CampsiteRecord r : mgr.getAll()) {
            if (r.dimension == dim && r.center.distanceSq(origin) <= 512 * 512) near.add(r);
        }
        near.sort(java.util.Comparator.comparingDouble(r -> r.center.distanceSq(origin)));

        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Campsites within 512b: "
                + TextFormatting.YELLOW + near.size() + TextFormatting.GRAY + " (total " + mgr.getAll().size() + ")"));
        int shown = 0;
        for (com.jubitus.travellers.camp.CampsiteRecord r : near) {
            if (shown++ >= 10) break;
            int dist = (int) Math.sqrt(r.center.distanceSq(origin));
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "  " + r.center.getX() + "," + r.center.getY()
                    + "," + r.center.getZ() + TextFormatting.GRAY + " dist=" + dist + " slots="
                    + (r.slotsComputed ? r.slots.size() : "?") ));
        }
    }

    /**
     * Full physical camp reset across every dimension: restores all campsite terrain to its
     * pre-camp state (paced across ticks by {@link com.jubitus.travellers.camp.CampResetCoordinator},
     * no player presence required). Plain "camp reset" keeps campsite records/slots so they don't
     * need rediscovery; "camp reset all" additionally deletes every campsite so the world starts
     * from zero (rediscovered from scratch as chunks load again). Progress is reported to
     * {@code sender} periodically until each dimension's job finishes.
     */
    private void executeCampReset(MinecraftServer server, ICommandSender sender, boolean deleteAll) {
        int alreadyRunning = 0;
        for (WorldServer ws : server.worlds) {
            if (com.jubitus.travellers.camp.CampResetCoordinator.INSTANCE.isActive(ws.provider.getDimension())) {
                alreadyRunning++;
                continue;
            }
            com.jubitus.travellers.camp.CampResetCoordinator.INSTANCE.start(ws, sender, deleteAll);
        }
        if (alreadyRunning > 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Camp reset already running in " + alreadyRunning + " dimension(s); skipped."));
        }
    }
}
