package com.jubitus.travellers.command;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.mill.VillageDataLoader;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.io.File;
import java.util.List;

public class CommandSpawnTraveller extends CommandBase {

    @Override
    public String getName() {
        return "spawntraveller";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/spawntraveller";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        World world = sender.getEntityWorld();
        if (!world.isRemote) {
            File millenaireFolder = new File(world.getSaveHandler().getWorldDirectory(), "millenaire");
            List<BlockPos> villages = VillageDataLoader.loadVillageCenters(millenaireFolder);
            spawnTraveller(world, villages);
            sender.sendMessage(new TextComponentString("Traveller spawned!"));
        }

    }

    public static void spawnTraveller(World world, List<BlockPos> villages) {
        if (villages.isEmpty()) return;
        BlockPos spawnPos = villages.get(0); // spawn at first village

        EntityTraveller traveller = new EntityTraveller(world);
        traveller.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        world.spawnEntity(traveller);
    }
}


