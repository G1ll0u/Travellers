package com.jubitus.travellers.debug.cmd;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug stable …`. */
public class StableDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "stable";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeStableDebug(server, sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "list", "dump", "log", "verify");
        return Collections.emptyList();
    }

    private void executeStableDebug(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Server world required.");
        java.util.UUID owner = player.getUniqueID();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        java.util.List<com.jubitus.travellers.zone.PlayerZoneRecord> stables = new java.util.ArrayList<>();
        for (WorldServer ws : server.worlds) {
            for (com.jubitus.travellers.zone.PlayerZoneRecord zone :
                    com.jubitus.travellers.zone.PlayerZoneManager.get(ws)
                            .getByOwnerAndType(owner, com.jubitus.travellers.zone.ZoneType.STABLE)) {
                if (zone.data instanceof com.jubitus.travellers.zone.stable.StableZoneData) stables.add(zone);
            }
        }
        if (stables.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "You own no Stable zones."));
            return;
        }

        WorldServer overworld = server.getWorld(0);
        com.jubitus.travellers.zone.stable.StableCoordinator coord =
                com.jubitus.travellers.zone.stable.StableCoordinator.get(overworld);

        if ("list".equals(sub)) {
            int hired = com.jubitus.travellers.zone.stable.StableCapacity.hiredWorkerCount(overworld, owner);
            int cap = hired * com.jubitus.travellers.config.TravellersConfig.STABLES.horseCapPerWorker;
            int total = com.jubitus.travellers.zone.stable.StableCapacity.currentHorseCount(overworld, owner);
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Stable horses: " + total + " / " + cap + "  (" + hired + " hired x "
                    + com.jubitus.travellers.config.TravellersConfig.STABLES.horseCapPerWorker
                    + ")   " + stables.size() + " stable(s).  dump/log/verify <n>"));
            for (int i = 0; i < stables.size(); i++) {
                com.jubitus.travellers.zone.PlayerZoneRecord z = stables.get(i);
                String name = (z.name == null || z.name.isEmpty()) ? "Stable" : z.name;
                sender.sendMessage(new TextComponentString(TextFormatting.AQUA + " [" + i + "] " + name
                        + " @ " + z.center.getX() + "," + z.center.getY() + "," + z.center.getZ()));
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "     "
                        + coord.debugSummary((WorldServer) w, z)));
            }
            return;
        }

        int idx;
        try {
            idx = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
        } catch (NumberFormatException e) {
            throw new CommandException("Expected a stable index (see /travellersdebug stable list).");
        }
        if (idx < 0 || idx >= stables.size()) throw new CommandException("No stable [" + idx + "].");
        com.jubitus.travellers.zone.PlayerZoneRecord zone = stables.get(idx);
        com.jubitus.travellers.zone.stable.StableZoneData data =
                (com.jubitus.travellers.zone.stable.StableZoneData) zone.data;

        switch (sub) {
            case "dump": {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "[" + idx + "] "
                        + coord.debugSummary((WorldServer) w, zone)));
                for (com.jubitus.travellers.zone.stable.StableHorseEntry e : data.pool) {
                    String state = e.liveEntityUUID != null ? "LIVE   " : (e.horseNbt != null ? "dormant" : "no-nbt ");
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + shortId(e.poolId)
                            + "  " + state + "  " + pad(e.origin.toString(), 18)
                            + " tamed=" + (e.tamed ? "Y" : "n") + " saddled=" + (e.saddled ? "Y" : "n")
                            + " temper=" + e.temper + " age=" + e.growingAge
                            + (e.breedingCooldownUntil > w.getTotalWorldTime() ? " (breed-cd)" : "")));
                }
                break;
            }
            case "log": {
                int n = 20;
                try {
                    if (args.length >= 4) n = Integer.parseInt(args[3]);
                } catch (NumberFormatException ignored) {
                }
                java.util.List<String> lines =
                        com.jubitus.travellers.zone.stable.StableWatchLog.tail(zone.id, n);
                if (lines.isEmpty()) {
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                            + "No events logged yet for [" + idx + "] (since last server start)."));
                } else {
                    sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                            + "Last " + lines.size() + " events for [" + idx + "]  (L=loaded, V=offscreen):"));
                    for (String l : lines) {
                        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + l));
                    }
                }
                break;
            }
            case "verify": {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "verify [" + idx + "]:"));
                for (String l : coord.debugVerify((WorldServer) w, zone)) {
                    boolean ok = l.startsWith("OK");
                    sender.sendMessage(new TextComponentString(
                            (ok ? TextFormatting.GREEN : TextFormatting.RED) + "  " + l));
                }
                break;
            }
            default:
                throw new CommandException("Unknown: stable " + sub + " (use list|dump|log|verify).");
        }
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }

    // --- Path comparison tool: mounted route vs. hand-marked ideal route ---
}
