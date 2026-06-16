package com.jubitus.travellers.server.net;

import com.jubitus.travellers.client.gui.GuiWorkerHub;
import com.jubitus.travellers.client.gui.bahavior.GuiWorkerBehavior;
import com.jubitus.travellers.client.gui.bahavior.relations.GuiWorkerVillageRelations;
import com.jubitus.travellers.client.gui.go.GuiWorkerGo;
import com.jubitus.travellers.client.gui.home.GuiHomeNamePrompt;
import com.jubitus.travellers.client.gui.home.GuiWorkerBarracks;
import com.jubitus.travellers.client.gui.orders.GuiWorkerOrders;
import com.jubitus.travellers.client.gui.recruit.GuiWorkerRecruit;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgOpenClientWorkerGui implements IMessage {

    private int entityId;
    private Type type;
    private BlockPos pos;

    public MsgOpenClientWorkerGui() {
    }

    public MsgOpenClientWorkerGui(int entityId, Type type) {
        this.entityId = entityId;
        this.type = type;
        this.pos = BlockPos.ORIGIN;
    }

    public MsgOpenClientWorkerGui(BlockPos pos, Type type) {
        this.entityId = -1;
        this.type = type;
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();

        int idx = buf.readUnsignedByte();
        Type[] vals = Type.values();
        this.type = (idx >= 0 && idx < vals.length) ? vals[idx] : Type.HUB;

        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeByte((byte) type.ordinal());
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
    }

    public enum Type {
        HUB,
        ORDERS,
        RECRUIT,
        BEHAVIOR,
        GO,
        HOME,
        VILLAGE_RELATIONS,
        HOME_NAME_PROMPT
    }

    public static class Handler implements IMessageHandler<MsgOpenClientWorkerGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenClientWorkerGui msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                EntityPlayer player = mc.player;
                World world = mc.world;

                if (player == null || world == null) return;

                switch (msg.type) {
                    case HUB: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerHub(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case ORDERS: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerOrders(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case RECRUIT: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerRecruit(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case BEHAVIOR: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerBehavior(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case GO: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerGo(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case HOME: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerBarracks(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case VILLAGE_RELATIONS: {
                        Entity e = world.getEntityByID(msg.entityId);
                        if (e instanceof EntityTravellerWorker) {
                            mc.displayGuiScreen(new GuiWorkerVillageRelations(player, (EntityTravellerWorker) e));
                        }
                        return;
                    }

                    case HOME_NAME_PROMPT: {
                        mc.displayGuiScreen(new GuiHomeNamePrompt(player, msg.pos));
                    }
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenClientWorkerGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenClientWorkerGui message, MessageContext ctx) {
            return null;
        }
    }
}