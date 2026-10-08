package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug mount …`. */
public class MountDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "mount";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeMountDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "give", "ride", "lead", "hitch");
        return Collections.emptyList();
    }

    private void executeMountDebug(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        if (!sub.equals("give") && !sub.equals("ride") && !sub.equals("lead") && !sub.equals("hitch")) {
            throw new CommandException("Usage: /travellersdebug mount give|ride|lead|hitch");
        }

        EntityTraveller closest = null;
        double bestSq = Double.MAX_VALUE;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(32.0D, 32.0D, 32.0D),
                t -> t != null && t.isEntityAlive())) {
            double dSq = t.getDistanceSq(player);
            if (dSq < bestSq) {
                bestSq = dSq;
                closest = t;
            }
        }

        if (closest == null) {
            throw new CommandException("No traveller within 32 blocks.");
        }

        // Mode switches: the same entry point the AI tasks use, so testing a mode by hand exercises
        // exactly the code path the game will.
        if (!sub.equals("give")) {
            if (closest.getMountUUID() == null) {
                throw new CommandException(closest.getName() + " has no bonded mount.");
            }
            com.jubitus.travellers.mount.MountMode mode =
                    sub.equals("ride") ? com.jubitus.travellers.mount.MountMode.MOUNTED
                            : sub.equals("lead") ? com.jubitus.travellers.mount.MountMode.LED
                            : com.jubitus.travellers.mount.MountMode.HITCHED;
            com.jubitus.travellers.mount.TravellerMountSupport.setMountMode(closest, mode);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + closest.getName() + " mount mode -> " + mode.name()));
            return;
        }

        if (closest.getMountUUID() != null) {
            throw new CommandException(closest.getName() + " already has a bonded mount.");
        }

        if (com.jubitus.travellers.mount.TravellerMountSupport.bondNewMount(closest)) {
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Bonded a horse to " + closest.getName() + "."));
        } else {
            String why = !com.jubitus.travellers.config.TravellersConfig.MOUNTS.enabled
                    ? "mounts disabled in config" : "could not spawn the horse (no room?)";
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Failed: " + why + "."));
        }
    }

    // --- Stable debug: list / dump <n> / log <n> [count] / verify <n> ---
}
