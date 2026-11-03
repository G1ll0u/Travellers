package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.virtual.MaterializePositionResolver;
import com.jubitus.travellers.village.MillFootprints;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Debug: teleport the sending player to a world position picked on a map (population map, formation hub). */
public class MsgTeleportToPos implements IMessage {
    private int x, z;

    public MsgTeleportToPos() {}

    public MsgTeleportToPos(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        z = buf.readInt();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<MsgTeleportToPos, IMessage> {
        @Override
        public IMessage onMessage(MsgTeleportToPos msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            if (!com.jubitus.travellers.debug.DebugAccess.allowed(player)) return null;

            player.getServerWorld().addScheduledTask(() -> {
                WorldServer world = player.getServerWorld();
                double[] spot = landingSpot(world, msg.x, msg.z);
                player.connection.setPlayerLocation(spot[0], spot[1], spot[2],
                        player.rotationYaw, player.rotationPitch);
            });

            return null;
        }
    }

    /** Where a player lands, the way a materializing unit would: out of buildings, off roofs, on top of water. */
    private static double[] landingSpot(WorldServer world, int x, int z) {
        BlockPos out = MillFootprints.pushOutside(world, new BlockPos(x, 64, z));
        int bx = out.getX(), bz = out.getZ();
        int radius = TravellersConfig.MATERIALIZATION.naturalGroundSearchRadius;
        // The player is about to stand here and load all of it anyway; getChunk also generates, like vanilla /tp.
        for (int cx = (bx - radius) >> 4; cx <= (bx + radius) >> 4; cx++) {
            for (int cz = (bz - radius) >> 4; cz <= (bz + radius) >> 4; cz++) {
                world.getChunk(cx, cz);
            }
        }

        double water = MaterializePositionResolver.waterSurfaceY(world, bx, bz);
        if (!Double.isNaN(water)) return new double[]{bx + 0.5, water, bz + 0.5};

        MaterializePositionResolver.Ground ground = MaterializePositionResolver.findNaturalGround(world, bx, bz, radius);
        if (ground != null) return new double[]{ground.x + 0.5, ground.y, ground.z + 0.5};
        return new double[]{bx + 0.5, world.getHeight(bx, bz), bz + 0.5};
    }
}
