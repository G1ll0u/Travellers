package com.jubitus.travellers.server.net.message.equip;

import com.jubitus.travellers.follow.gui.GuiWorkerFormationHub;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class MsgEquipDestsResponse implements IMessage {

    public static class EquipDestInfo {
        public BlockPos pos;
        public com.jubitus.travellers.unit.equipment.EquipTargetKind kind;
        public String name;

        public EquipDestInfo() {}
        public EquipDestInfo(BlockPos pos, com.jubitus.travellers.unit.equipment.EquipTargetKind kind, String name) {
            this.pos = pos;
            this.kind = kind;
            this.name = name;
        }
    }

    private List<EquipDestInfo> dests;

    public MsgEquipDestsResponse() { dests = new ArrayList<>(); }
    public MsgEquipDestsResponse(List<EquipDestInfo> dests) { this.dests = dests; }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        dests = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            EquipDestInfo d = new EquipDestInfo();
            d.pos = BlockPos.fromLong(buf.readLong());
            d.kind = com.jubitus.travellers.unit.equipment.EquipTargetKind.values()[buf.readByte() & 0xFF];
            d.name = ByteBufUtils.readUTF8String(buf);
            dests.add(d);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dests == null ? 0 : dests.size());
        if (dests != null) {
            for (EquipDestInfo d : dests) {
                buf.writeLong(d.pos.toLong());
                buf.writeByte(d.kind.ordinal());
                ByteBufUtils.writeUTF8String(buf, d.name == null ? "" : d.name);
            }
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgEquipDestsResponse, IMessage> {
        @Override
        public IMessage onMessage(MsgEquipDestsResponse msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof GuiWorkerFormationHub) {
                    ((GuiWorkerFormationHub) screen).onEquipDestsReceived(msg.dests);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgEquipDestsResponse, IMessage> {
        @Override public IMessage onMessage(MsgEquipDestsResponse msg, MessageContext ctx) { return null; }
    }
}
