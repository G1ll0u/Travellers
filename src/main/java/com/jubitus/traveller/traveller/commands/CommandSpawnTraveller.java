package com.jubitus.traveller.traveller.commands;

import com.jubitus.traveller.traveller.utils.MerchantSpawner;
import com.jubitus.traveller.traveller.utils.VillageDataLoader;
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
        return "spawnmerchant";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/spawnmerchant";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        World world = sender.getEntityWorld();
        if (!world.isRemote) {
            File millenaireFolder = new File(world.getSaveHandler().getWorldDirectory(), "millenaire");
            List<BlockPos> villages = VillageDataLoader.loadVillageCenters(millenaireFolder);
            MerchantSpawner.spawnMerchant(world, villages);
            sender.sendMessage(new TextComponentString("Merchant spawned!"));
        }
    }
}


