package com.jubitus.traveller.traveller.utils.commands;

import com.jubitus.traveller.traveller.entityAI.EntityTravellerMerchant;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

public class CommandMerchantDebug implements net.minecraft.command.ICommand {

    @Override
    public String getName() {
        return "merchant_debug";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/merchant_debug [uuid]";
    }

    @Override
    public List<String> getAliases() {
        return java.util.Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        World world = sender.getEntityWorld();
        if (world.isRemote) {
            return;
        }

        EntityTravellerMerchant target;

        // If UUID provided: try to find that merchant in all worlds
        if (args.length >= 1) {
            java.util.UUID id;
            try {
                id = java.util.UUID.fromString(args[0]);
            } catch (IllegalArgumentException e) {
                throw new CommandException("Invalid UUID: " + args[0]);
            }

            target = findMerchantByUUID(server, id);
            if (target == null) {
                throw new CommandException("No traveller_merchant with UUID " + id + " found.");
            }
        } else {
            // otherwise: nearest merchant in your current world
            if (!(sender instanceof Entity)) {
                throw new CommandException("Must be an entity to use without UUID.");
            }
            Entity ent = (Entity) sender;
            target = findNearestMerchant(world, ent.posX, ent.posY, ent.posZ);
            if (target == null) {
                throw new CommandException("No traveller_merchant found in this world.");
            }
        }

        sendMerchantInfo(sender, target);
    }

    private EntityTravellerMerchant findMerchantByUUID(MinecraftServer server, java.util.UUID id) {
        for (WorldServer ws : server.worlds) {
            for (Entity entity : ws.loadedEntityList) {
                if (entity instanceof EntityTravellerMerchant) {
                    if (id.equals(entity.getUniqueID())) {
                        return (EntityTravellerMerchant) entity;
                    }
                }
            }
        }
        return null;
    }

    private EntityTravellerMerchant findNearestMerchant(World world, double x, double y, double z) {
        double bestD2 = Double.MAX_VALUE;
        EntityTravellerMerchant best = null;

        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntityTravellerMerchant)) continue;
            double d2 = entity.getDistanceSq(x, y, z);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = (EntityTravellerMerchant) entity;
            }
        }
        return best;
    }

    private void sendMerchantInfo(ICommandSender sender, EntityTravellerMerchant m) {
        BlockPos pos = m.getPosition();
        int dim = m.world.provider.getDimension();
        ICommandSender s = sender;

        s.sendMessage(new TextComponentString(
                TextFormatting.GOLD + "[Traveller Merchant Debug]"
        ));

        s.sendMessage(new TextComponentString(
                String.format("UUID: %s", m.getUniqueID().toString())
        ));

        s.sendMessage(new TextComponentString(
                String.format("Pos: (%.1f, %.1f, %.1f) dim=%d",
                        m.posX, m.posY, m.posZ, dim)
        ));

        if (sender instanceof Entity) {
            Entity e = (Entity) sender;
            double dist = m.getDistance(e);
            s.sendMessage(new TextComponentString(
                    String.format("Distance to you: %.1f blocks", dist)
            ));
        }

        // --- chunkloading status ---
        boolean chunkloading = m.isChunkLoadingActive();
        ChunkPos forced = m.getForcedChunk();

        s.sendMessage(new TextComponentString(
                "Chunkloading: " + (chunkloading ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "INACTIVE")
        ));

        if (chunkloading && forced != null) {
            boolean chunkLoaded = ((WorldServer) m.world).getChunkProvider().chunkExists(forced.x, forced.z);
            s.sendMessage(new TextComponentString(
                    String.format("Forced chunk: [%d, %d] (loaded=%s)",
                            forced.x, forced.z, chunkLoaded)
            ));
        }

        // --- travel / progress status ---
        BlockPos tv = m.getTargetVillage();
        if (tv != null) {
            double d = Math.sqrt(m.getDistanceSqToCenter(tv));
            s.sendMessage(new TextComponentString(
                    String.format("Target village: (%d, %d, %d)  dist=%.1f",
                            tv.getX(), tv.getY(), tv.getZ(), d)
            ));
        } else {
            s.sendMessage(new TextComponentString("Target village: <none>"));
        }

        double d2Debug = m.getLastDebugDistSqToTarget();
        int ticksNoProgress = m.getTicksSinceDebugProgress();
        double secondsNoProgress = ticksNoProgress / 20.0;

        String progressStr;
        if (tv == null || d2Debug == Double.MAX_VALUE) {
            progressStr = "Not travelling / no target.";
        } else if (secondsNoProgress < 5.0) {
            progressStr = String.format("Making progress (last progress %.1fs ago).", secondsNoProgress);
        } else {
            progressStr = String.format("Likely STALLED (no progress for %.1fs).", secondsNoProgress);
        }

        s.sendMessage(new TextComponentString("Progress: " + progressStr));
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        // adjust level if you want OP-only
        return true;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        return java.util.Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    @Override
    public int compareTo(ICommand o) {
        return this.getName().compareTo(o.getName());
    }
}
