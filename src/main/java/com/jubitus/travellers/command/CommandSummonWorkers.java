package com.jubitus.travellers.command;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.common.eventhandler.Event;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;
public class CommandSummonWorkers extends CommandBase {

    private static final int MAX_COUNT = 256;

    @Override
    public String getName() {
        return "summonworkers";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/summonworkers <count> <player>";
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
            EntityTravellerWorker worker = new EntityTravellerWorker(ws);

            worker.setLocationAndAngles(
                    x,
                    y,
                    z,
                    ws.rand.nextFloat() * 360.0F,
                    0.0F
            );

            worker.onInitialSpawn(ws.getDifficultyForLocation(new BlockPos(target)), null);
            worker.setHomeVillage(null);
            worker.hireAsFollower(target);
            worker.setFollowOrderActive(true);
            worker.clearEscortLeader();

            if (ws.spawnEntity(worker)) {
                success++;
            }
        }

        if (success <= 0) {
            throw new CommandException("Failed to spawn any worker.");
        }

        sender.sendMessage(new TextComponentString(
                "Spawned " + success + " hired workers for " + target.getName() + "."
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