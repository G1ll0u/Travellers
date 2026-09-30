package com.jubitus.travellers.command;

import com.jubitus.travellers.server.map.stream.MsgOpenPopulationMap;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import net.minecraft.entity.player.EntityPlayerMP;

// TravellerCommandChunks.java
public class TravellerCommandChunks extends net.minecraft.command.CommandBase {

    @Override
    public String getName() {
        return "travellerChunks";
    }

    @Override
    public String getUsage(net.minecraft.command.ICommandSender sender) {
        return "/travellerschunks";
    }

    @Override
    public void execute(net.minecraft.server.MinecraftServer server,
                        net.minecraft.command.ICommandSender sender,
                        String[] args) throws net.minecraft.command.CommandException {

        if (!(sender instanceof EntityPlayerMP)) {
            return; // players only, nothing in chat
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;

        // Opens the population world map GUI client-side; population data streams in afterward
        // via MsgSubscribePopulationMap / PopulationMapStream.
        TravellerFollowNet.sendToPlayer(player, new MsgOpenPopulationMap());
    }

    @Override
    public int getRequiredPermissionLevel() {
        // 0 = anyone can use; change to 2 if you want op-only
        return 0;
    }
}

