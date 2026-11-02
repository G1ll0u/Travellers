package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
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

/** `/travellersdebug battle …`. */
public class BattleDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "battle";
    }

    @Override
    public String description() {
        return "List and manage virtual battles";
    }

    @Override
    public String usage() {
        return "[list|info <n>|end <n>|spawnfight [x z]|loot]";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeBattle(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "list", "info", "end", "spawnfight", "loot");
        return Collections.emptyList();
    }

    private void executeBattle(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Server world required.");
        WorldServer ws = (WorldServer) w;
        com.jubitus.travellers.battle.VirtualBattleManager mgr =
                com.jubitus.travellers.battle.VirtualBattleManager.get(ws);
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        if (sub.equals("spawnfight")) {
            int x, z;
            if (args.length >= 4) {
                x = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } else {
                x = (int) sender.getPositionVector().x + 100;
                z = (int) sender.getPositionVector().z;
            }
            spawnTestFight(ws, x, z, sender);
            return;
        }

        if (sub.equals("loot")) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Pending loot drops: " + mgr.getPendingLootCount()));
            return;
        }

        java.util.List<com.jubitus.travellers.battle.BattleRecord> list = new java.util.ArrayList<>();
        for (com.jubitus.travellers.battle.BattleRecord b : mgr.getBattlesView()) list.add(b);

        if (sub.equals("info") || sub.equals("end")) {
            int idx = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
            if (idx < 0 || idx >= list.size()) throw new CommandException("No battle #" + idx
                    + " (" + list.size() + " active).");
            com.jubitus.travellers.battle.BattleRecord b = list.get(idx);
            if (sub.equals("end")) {
                mgr.debugEndBattle(ws, b);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Battle #" + idx + " ended."));
            } else {
                sender.sendMessage(new TextComponentString(describeBattle(ws, b, true)));
            }
            return;
        }

        // list (default)
        if (list.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No active battles."));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "#" + i + " " + describeBattle(ws, list.get(i), false)));
        }
    }

    private static String describeBattle(WorldServer ws, com.jubitus.travellers.battle.BattleRecord b,
                                         boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("Battle @ ").append(b.center.getX()).append(",").append(b.center.getZ())
                .append(" ").append(com.jubitus.travellers.debug.BattleLog.counts(b))
                .append(" age=").append((ws.getTotalWorldTime() - b.startTick) / 20).append("s");
        if (b.campAnchorId != null) sb.append(" [camp assault]");
        if (detailed) {
            java.util.Map<String, Integer> camps = b.camps(null);
            VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
            for (com.jubitus.travellers.battle.BattleParticipant p : b.participants.values()) {
                com.jubitus.travellers.virtual.VirtualTravellerRecord r =
                        vtm.getRecord(p.uuid);
                sb.append("\n  [").append(camps.get(p.groupKey) == 0 ? "A" : "B").append("] ")
                        .append(p.groupKey)
                        .append(p.isGroupLeader ? " LEADER" : "")
                        .append(p.alive ? "" : " DEAD")
                        .append(r != null
                                ? String.format(" hp=%.1f @%d,%d (%s)", r.getHealthResolved(),
                                        (int) r.posX, (int) r.posZ, r.getCombatProfile())
                                : (p.alive ? " [loaded]" : ""));
            }
        }
        return sb.toString();
    }

    /** Spawns two hostile virtual groups ~30 blocks apart so a battle starts on the next detection pass. */
    private static void spawnTestFight(WorldServer ws, int x, int z, ICommandSender sender) {
        VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
        net.minecraft.world.DifficultyInstance diff = new net.minecraft.world.DifficultyInstance(
                ws.getDifficulty(), ws.getWorldTime(), 0, 0.5f);
        int y = ws.getHeight(x, z);

        for (int i = 0; i < 4; i++) {
            spawnTestRecord(ws, vtm, diff, false, x - 15, y, z + i * 2);
            spawnTestRecord(ws, vtm, diff, true, x + 15, y, z + i * 2);
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                + "Spawned 4 virtual travellers vs 4 virtual raiders near " + x + "," + z + "."));
    }

    private static void spawnTestRecord(WorldServer ws, VirtualTravellerManager vtm,
                                        net.minecraft.world.DifficultyInstance diff,
                                        boolean raider, int x, int y, int z) {
        EntityTraveller e = raider
                ? new com.jubitus.travellers.unit.EntityTravellerRaider(ws)
                : new EntityTraveller(ws);
        e.setPosition(x + 0.5, y, z + 0.5);
        e.onInitialSpawn(diff, null);
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        e.writeToNBT(nbt);
        com.jubitus.travellers.virtual.VirtualTravellerRecord r =
                new com.jubitus.travellers.virtual.VirtualTravellerRecord(
                        e.getUniqueID(), x + 0.5, y, z + 0.5, 0f, ws.provider.getDimension(), nbt);
        r.entityClass = raider ? "RAIDER" : "TRAVELLER";
        r.name = e.getName();
        r.health = e.getMaxHealth();
        vtm.addRecord(r);
        e.setDead();
    }

    // --- Reset: wipe all virtual travellers/raiders and kill loaded ones (camps untouched, see "camp reset") ---
}
