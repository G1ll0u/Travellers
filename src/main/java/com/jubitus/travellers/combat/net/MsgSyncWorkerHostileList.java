package com.jubitus.travellers.combat.net;

import com.jubitus.travellers.client.gui.behavior.GuiWorkerEnemies;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgSyncWorkerHostileList implements IMessage {
    private java.util.List<String> names = new java.util.ArrayList<>();

    public MsgSyncWorkerHostileList() {
    }

    public MsgSyncWorkerHostileList(java.util.List<String> names) {
        this.names = names;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        names = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(names.size());
        for (String s : names) {
            ByteBufUtils.writeUTF8String(buf, s);
        }
    }

    // CLIENT handler
    public static class Handler implements IMessageHandler<MsgSyncWorkerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHostileList msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof GuiWorkerEnemies) {
                    ((GuiWorkerEnemies) screen).receiveHostileNames(msg.names);
                }
            });
            return null;
        }
    }

    // SERVER noop
    public static class NoopHandler implements IMessageHandler<MsgSyncWorkerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHostileList msg, MessageContext ctx) {
            return null;
        }
    }
}
