package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.utils.debug.TravellerChunkSnapshot;
import com.jubitus.travellers.utils.net.packet.PacketTravellerChunks;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

// TravellerCommandChunks.java
public class TravellerCommandChunks extends net.minecraft.command.CommandBase {

    @Override
    public String getName() {
        return "travellerChunks";
    }

    @Override
    public String getUsage(net.minecraft.command.ICommandSender sender) {
        return "/travellersChunks [radiusInBlocks]";
    }

    @Override
    public void execute(net.minecraft.server.MinecraftServer server,
                        net.minecraft.command.ICommandSender sender,
                        String[] args) throws net.minecraft.command.CommandException {

        if (!(sender instanceof EntityPlayerMP)) {
            return; // players only, nothing in chat
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer world = (WorldServer) player.world;

        int radiusBlocks = 256; // default
        if (args.length >= 1) {
            radiusBlocks = parseInt(args[0], 16, 8192); // clamp a bit
        }

        BlockPos center = player.getPosition();

        // Build snapshot on the server then send to client
        TravellerChunkSnapshot snapshot = TravellerChunkSnapshot.create(world, center, radiusBlocks);

        Travellers.NETWORK.sendTo(new PacketTravellerChunks(snapshot), player);
    }

    @Override
    public int getRequiredPermissionLevel() {
        // 0 = anyone can use; change to 2 if you want op-only
        return 0;
    }
}

