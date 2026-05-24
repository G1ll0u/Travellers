package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

public class CommandSummonRaiders extends CommandBase {

    private static final int MAX_COUNT = 256;

    @Override
    public String getName() {
        return "summonraiders";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/summonraiders <count> <player>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return sender.canUseCommand(this.getRequiredPermissionLevel(), this.getName());
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 2) {
            throw new WrongUsageException(getUsage(sender));
        }

        int count = parseInt(args[0], 1, MAX_COUNT);
        EntityPlayerMP target = getPlayer(server, sender, args[1]);
        if (target == null) {
            throw new PlayerNotFoundException("Player not found.");
        }

        WorldServer ws = target.getServerWorld();
        int success = 0;

        double x = target.posX;
        double y = target.getEntityBoundingBox().minY;
        double z = target.posZ;

        for (int i = 0; i < count; i++) {
            EntityTravellerRaider worker = new EntityTravellerRaider(ws);

            worker.setLocationAndAngles(
                    x,
                    y,
                    z,
                    ws.rand.nextFloat() * 360.0F,
                    0.0F
            );

            if (ws.spawnEntity(worker)) {
                success++;
            }
        }

        if (success <= 0) {
            throw new CommandException("Failed to spawn any raider.");
        }

        sender.sendMessage(new TextComponentString(
                "Spawned " + success + " raiders "
        ));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server,
                                          ICommandSender sender,
                                          String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        return java.util.Collections.emptyList();
    }
}