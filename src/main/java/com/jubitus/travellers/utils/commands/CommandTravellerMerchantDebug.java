package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.UUID;

public class CommandTravellerMerchantDebug extends CommandBase {

    @Override
    public String getName() {
        return "tmerchant";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/tmerchant [nearest|<uuid>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        World world = sender.getEntityWorld();
        if (world.isRemote) return;

        EntityTravellerMerchant target = null;

        if (args.length == 0 || "nearest".equalsIgnoreCase(args[0])) {
            target = findNearestMerchant(world, sender.getPositionVector());
        } else {
            try {
                UUID id = UUID.fromString(args[0]);
                target = findMerchantByUUID(world, id);
            } catch (IllegalArgumentException ex) {
                throw new CommandException("Invalid UUID: " + args[0]);
            }
        }

        if (target == null) {
            sender.sendMessage(new TextComponentString("[Travellers] No merchant found."));
            return;
        }

        TravellerChunkManager.WorldState ws = TravellerChunkManager.INSTANCE.getWorldState(world);
        TravellerChunkManager.MerchantData md =
                TravellerChunkManager.INSTANCE.getMerchantData(world, target.getUniqueID());

        BlockPos pos = target.getPosition();
        sender.sendMessage(new TextComponentString(
                String.format("Merchant %s at (%d, %d, %d) in dim %d",
                        target.getUniqueID().toString().substring(0, 8),
                        pos.getX(), pos.getY(), pos.getZ(),
                        world.provider.getDimension())));

        // distance to nearest player
        EntityPlayer nearest = world.getClosestPlayer(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                99999, false);
        if (nearest != null) {
            double d = nearest.getDistance(target);
            sender.sendMessage(new TextComponentString(
                    String.format("Nearest player: %s (%.1f blocks)",
                            nearest.getName(), d)));
        } else {
            sender.sendMessage(new TextComponentString("Nearest player: none in world"));
        }

        // distance to target village
        BlockPos tv = target.getTargetVillage();
        if (tv != null) {
            double d2 = target.getDistanceSqToCenter(tv);
            sender.sendMessage(new TextComponentString(
                    String.format("Target village at (%d, %d, %d), dist ≈ %.1f",
                            tv.getX(), tv.getY(), tv.getZ(),
                            Math.sqrt(d2))));
        } else {
            sender.sendMessage(new TextComponentString("No target village"));
        }

        // chunkloading info
        if (md != null) {
            long now = world.getTotalWorldTime();
            long since = now - md.lastProgressTick;
            sender.sendMessage(new TextComponentString(
                    String.format("Chunkloading: %s, radius=%d, last progress %ds ago",
                            md.chunkloading ? "ON" : "OFF",
                            md.radius,
                            since / 20)));
        } else {
            sender.sendMessage(new TextComponentString("No merchant data in chunk manager"));
        }

        if (ws != null) {
            sender.sendMessage(new TextComponentString(
                    String.format("World forced chunks for merchants: %d",
                            ws.forced.size())));
        }
    }

    private EntityTravellerMerchant findNearestMerchant(World world, Vec3d from) {
        double best = Double.MAX_VALUE;
        EntityTravellerMerchant bestEnt = null;
        for (Entity e : world.loadedEntityList) {
            if (e instanceof EntityTravellerMerchant && e.isEntityAlive()) {
                double d2 = e.getDistanceSq(from.x, from.y, from.z);
                if (d2 < best) {
                    best = d2;
                    bestEnt = (EntityTravellerMerchant) e;
                }
            }
        }
        return bestEnt;
    }

    private EntityTravellerMerchant findMerchantByUUID(World world, UUID id) {
        for (Entity e : world.loadedEntityList) {
            if (e instanceof EntityTravellerMerchant && e.getUniqueID().equals(id)) {
                return (EntityTravellerMerchant) e;
            }
        }
        return null;
    }
}
