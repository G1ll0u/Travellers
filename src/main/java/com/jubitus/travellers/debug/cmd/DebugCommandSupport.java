package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;

/** Helpers shared by several `/travellersdebug` subcommands. */
public final class DebugCommandSupport {

    private DebugCommandSupport() {
    }

    public static void sendSummary(ICommandSender sender, String summary) {
        for (String line : summary.split("\n")) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + line));
        }
    }

    public static EntityTraveller resolveTraceTarget(ICommandSender sender, String[] args, int nameIndex)
            throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) return resolveTraceTargetAnywhere(sender, args, nameIndex);
        EntityPlayerMP player = (EntityPlayerMP) sender;

        List<EntityTraveller> nearby = player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(64.0D), t -> t != null && t.isEntityAlive());
        if (nearby.isEmpty()) throw new CommandException("No traveller within 64 blocks.");

        if (args.length > nameIndex) {
            String wanted = args[nameIndex];
            for (EntityTraveller t : nearby) {
                if (t.getName().equalsIgnoreCase(wanted)) return t;
            }
            for (EntityTraveller t : nearby) {
                if (t.getName().toLowerCase().startsWith(wanted.toLowerCase())) return t;
            }
            throw new CommandException("No traveller named '" + wanted + "' within 64 blocks.");
        }

        Vec3d eye = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        EntityTraveller bestLooked = null;
        double bestLookedDist = Double.MAX_VALUE;
        EntityTraveller nearest = null;
        double nearestSq = Double.MAX_VALUE;

        for (EntityTraveller t : nearby) {
            double dSq = t.getDistanceSq(player);
            if (dSq < nearestSq) {
                nearestSq = dSq;
                nearest = t;
            }
            // Generous inflation: a traveller several dozen blocks off only needs to be roughly under
            // the crosshair for the intent to be unambiguous.
            AxisAlignedBB box = t.getEntityBoundingBox().grow(0.6D);
            net.minecraft.util.math.RayTraceResult hit = box.calculateIntercept(eye, eye.add(look.scale(64.0D)));
            if (hit != null && Math.sqrt(dSq) < bestLookedDist) {
                bestLookedDist = Math.sqrt(dSq);
                bestLooked = t;
            }
        }
        return bestLooked != null ? bestLooked : nearest;
    }

    /** Console / RCON: a loaded unit anywhere, by full name (spaces allowed) or UUID prefix. */
    public static EntityTraveller resolveTraceTargetAnywhere(ICommandSender sender, String[] args, int nameIndex)
            throws CommandException {
        if (args.length <= nameIndex) throw new CommandException("From the console, give a name or a UUID prefix.");
        String wanted = String.join(" ", java.util.Arrays.copyOfRange(args, nameIndex, args.length)).toLowerCase();
        for (net.minecraft.world.WorldServer world : sender.getServer().worlds) {
            for (Entity e : world.loadedEntityList) {
                if (e instanceof EntityTraveller && e.isEntityAlive()
                        && (e.getName().equalsIgnoreCase(wanted) || e.getUniqueID().toString().startsWith(wanted))) {
                    return (EntityTraveller) e;
                }
            }
        }
        throw new CommandException("No loaded traveller named or with UUID '" + wanted + "'.");
    }

    public static double parseCoord(ICommandSender sender, String s, boolean isX) throws CommandException {
        double base = 0.0;
        if (sender instanceof Entity) {
            Entity e = (Entity) sender;
            base = isX ? e.posX : e.posZ;
        }
        return parseCoordinate(base, s, false).getResult();
    }

    public static List<String> nearbyTravellerNames(ICommandSender sender) {
        List<String> names = new java.util.ArrayList<>();
        if (!(sender instanceof EntityPlayerMP)) return names;
        EntityPlayerMP player = (EntityPlayerMP) sender;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(64.0D), t -> t != null && t.isEntityAlive())) {
            String name = t.getName();
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }
}
