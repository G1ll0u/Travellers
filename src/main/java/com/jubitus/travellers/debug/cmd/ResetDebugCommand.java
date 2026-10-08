package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug reset …`. */
public class ResetDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "reset";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("workers")) {
            executeResetWorkers(server, sender);
        } else {
            executeReset(server, sender);
        }
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "workers");
        return Collections.emptyList();
    }

    private void executeReset(MinecraftServer server, ICommandSender sender) {
        int virtualRemoved = 0;
        int loadedKilled = 0;

        for (WorldServer ws : server.worlds) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
            virtualRemoved += mgr.clearByClass("TRAVELLER", "RAIDER");

            for (Entity e : new java.util.ArrayList<>(ws.loadedEntityList)) {
                if (e instanceof EntityTravellerWorker) continue;
                if (e instanceof EntityTraveller && !e.isDead) {
                    // setDead() bypasses the normal death event entirely, so nothing else here would
                    // ever release a bonded mount - left alone it becomes a permanently orphaned horse.
                    TravellerMountSupport.detachAndKillMountForDebugWipe((EntityTraveller) e);
                    e.setDead();
                    loadedKilled++;
                }
            }
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Reset complete: " +
                TextFormatting.YELLOW + virtualRemoved + TextFormatting.GREEN + " virtual records removed, " +
                TextFormatting.YELLOW + loadedKilled + TextFormatting.GREEN + " loaded entities killed." +
                TextFormatting.GRAY + " Workers and campsites untouched (use 'camp reset' for camps)."));
    }

    // --- Reset workers: wipe non-hired virtual/loaded workers, hired workers are preserved ---

    private void executeResetWorkers(MinecraftServer server, ICommandSender sender) {
        int virtualRemoved = 0;
        int loadedKilled = 0;

        for (WorldServer ws : server.worlds) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
            virtualRemoved += mgr.clearWorkersExceptHired();

            for (Entity e : new java.util.ArrayList<>(ws.loadedEntityList)) {
                if (!(e instanceof EntityTravellerWorker) || e.isDead) continue;
                EntityTravellerWorker worker = (EntityTravellerWorker) e;
                if (worker.isHiredFollower()) continue;
                TravellerMountSupport.detachAndKillMountForDebugWipe(worker);
                worker.setDead();
                loadedKilled++;
            }
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Worker reset complete: " +
                TextFormatting.YELLOW + virtualRemoved + TextFormatting.GREEN + " virtual records removed, " +
                TextFormatting.YELLOW + loadedKilled + TextFormatting.GREEN + " loaded workers killed." +
                TextFormatting.GRAY + " Hired workers preserved."));
    }

    // --- Camp listing: show campsites near the player ---
}
