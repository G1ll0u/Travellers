package com.jubitus.traveller.traveller.utils.commands;

import com.jubitus.traveller.traveller.utils.debug.MsgToggleFollow;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.text.TextComponentString;


public class CommandFollowTraveller extends CommandBase {

    @Override
    public String getName() {
        return "followtraveller";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/followtraveller [stop|radius <r>]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Must be run by a player.");
        EntityPlayerMP p = (EntityPlayerMP) sender;

        // /followtraveller stop
        if (args.length >= 1 && "stop".equalsIgnoreCase(args[0])) {
            TravellerFollowNet
                    .sendToPlayer(p, new MsgToggleFollow(-1));
            p.sendMessage(new TextComponentString("Follow mode disabled."));
            return;
        }

        double radius = 64.0;
        if (args.length >= 2 && "radius".equalsIgnoreCase(args[0])) {
            try {
                radius = Math.max(4.0, Double.parseDouble(args[1]));
            } catch (NumberFormatException ignored) {
            }
        }

        AxisAlignedBB box = p.getEntityBoundingBox().grow(radius, 16.0, radius);
        java.util.List<com.jubitus.traveller.traveller.entityAI.EntityTraveller> list =
                p.world.getEntitiesWithinAABB(com.jubitus.traveller.traveller.entityAI.EntityTraveller.class, box, e -> e != null && e.isEntityAlive());

        if (list.isEmpty()) throw new CommandException("No travellers within " + (int) radius + " blocks.");

        com.jubitus.traveller.traveller.entityAI.EntityTraveller best = null;
        double bestD2 = Double.MAX_VALUE;
        for (com.jubitus.traveller.traveller.entityAI.EntityTraveller t : list) {
            double d2 = t.getDistanceSq(p);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = t;
            }
        }
        if (best == null) throw new CommandException("No valid traveller found.");

        TravellerFollowNet
                .sendToPlayer(p, new MsgToggleFollow(best.getEntityId()));

        p.sendMessage(new TextComponentString(
                "Following traveller at " + best.getPosition().getX() + " "
                        + best.getPosition().getY() + " " + best.getPosition().getZ()
                        + " (id " + best.getEntityId() + ")."));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    } // any player

    @Override
    public java.util.List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, net.minecraft.util.math.BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "stop", "radius");
        if (args.length == 2 && "radius".equalsIgnoreCase(args[0]))
            return java.util.Arrays.asList("32", "48", "64", "96", "128");
        return java.util.Collections.emptyList();
    }
}
