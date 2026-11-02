package com.jubitus.travellers.command;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.NumberInvalidException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

public class CommandMountTraveller extends CommandBase {

    @Override
    public String getName() {
        return "mounttraveller";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mounttraveller [range]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);

        double range = 3.5D;
        if (args.length >= 1) {
            try {
                range = Math.max(1.0D, parseDouble(args[0], 1.0D, 16.0D));
            } catch (NumberInvalidException ignored) {
            }
        }

        AxisAlignedBB box = new AxisAlignedBB(
                player.posX - range, player.posY - range, player.posZ - range,
                player.posX + range, player.posY + range, player.posZ + range
        );

        List<EntityTraveller> candidates = player.world.getEntitiesWithinAABB(EntityTraveller.class, box, e ->
                e != null && e.isEntityAlive());

        if (candidates.isEmpty()) {
            throw new CommandException(String.format("No Traveller within %.1f blocks.", range));
        }

        // pick nearest
        EntityTraveller best = candidates.get(0);
        double bestD2 = best.getDistanceSq(player);
        for (int i = 1; i < candidates.size(); i++) {
            EntityTraveller t = candidates.get(i);
            double d2 = t.getDistanceSq(player);
            if (d2 < bestD2) {
                best = t;
                bestD2 = d2;
            }
        }

        boolean ok = player.startRiding(best, true); // force = true so it replaces current vehicle if any
        if (!ok) throw new CommandException("Couldn't mount the Traveller (vehicle refused).");

        notifyCommandListener(sender, this, "Mounted Traveller.");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // let anyone use it; change to 2 if you want OP-only
    }

    @Override
    public java.util.List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                                    @javax.annotation.Nullable net.minecraft.util.math.BlockPos targetPos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "3.5", "8", "16") : java.util.Collections.emptyList();
    }
}
