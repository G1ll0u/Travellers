package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgRegisterHomeChest implements IMessage {
    private BlockPos pos;
    private String name;

    public MsgRegisterHomeChest() {
    }

    public MsgRegisterHomeChest(BlockPos pos, String name) {
        this.pos = pos;
        this.name = name == null ? "" : name;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        int len = buf.readUnsignedShort();
        this.name = buf.readCharSequence(len, java.nio.charset.StandardCharsets.UTF_8).toString();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static class Handler implements IMessageHandler<MsgRegisterHomeChest, IMessage> {
        @Override
        public IMessage onMessage(MsgRegisterHomeChest msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;

                // same restrictions as your wand handler
                if (world.provider.getDimension() != 0) return;

                IBlockState st = world.getBlockState(msg.pos);
                Block b = st.getBlock();
                if (b != Blocks.CHEST && b != Blocks.TRAPPED_CHEST) return;

                String clean = msg.name.trim();
                if (clean.isEmpty()) return;

                TravellerHomeManager.get(world).addOrUpdateHomeChest(player, msg.pos, clean);

                player.sendMessage(new TextComponentString(TextFormatting.GREEN +
                        "Home saved as: " + clean));
                world.playSound(null, msg.pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        SoundCategory.PLAYERS, 0.5F, 1.2F);
            });
            return null;
        }
    }
}
