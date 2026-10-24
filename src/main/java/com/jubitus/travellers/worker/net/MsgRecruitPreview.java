package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.worker.RecruitCultures;
import com.jubitus.travellers.worker.RecruitPlan;
import com.jubitus.travellers.worker.gui.GuiWorkerRecruitCultures;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Server → recruit screen: the cultures in range and the tour planned for the wanted counts. */
public class MsgRecruitPreview implements IMessage {

    public int workerId;
    public List<RecruitCultures.Option> options = new ArrayList<>();
    /** The counts the server kept, which the screen shows as its counters. */
    public Map<String, Integer> wanted;
    public List<RecruitPlan.Stop> stops = new ArrayList<>();
    public int travelFee;
    public int purse;

    public MsgRecruitPreview() {
    }

    MsgRecruitPreview(int workerId, List<RecruitCultures.Option> options, RecruitPlan plan) {
        this.workerId = workerId;
        this.options = options;
        this.wanted = plan.wanted;
        this.stops = plan.stops;
        this.travelFee = plan.travelFee;
        this.purse = plan.purse;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        int n = Math.min(buf.readShort(), 64);
        RecruitCultures.Verdict[] verdicts = RecruitCultures.Verdict.values();
        for (int i = 0; i < n; i++) {
            String key = ByteBufUtils.readUTF8String(buf);
            int villages = buf.readShort();
            int nearest = buf.readInt();
            int free = buf.readInt();
            int chance = buf.readByte();
            int v = buf.readByte();
            options.add(new RecruitCultures.Option(key, villages, nearest, free, chance,
                    v >= 0 && v < verdicts.length ? verdicts[v] : RecruitCultures.Verdict.LOW_REPUTATION));
        }
        wanted = MsgRecruitPreviewRequest.readWanted(buf);
        int s = Math.min(buf.readShort(), 256);
        for (int i = 0; i < s; i++) {
            BlockPos center = BlockPos.fromLong(buf.readLong());
            stops.add(new RecruitPlan.Stop(center, ByteBufUtils.readUTF8String(buf)));
        }
        travelFee = buf.readInt();
        purse = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeShort(options.size());
        for (RecruitCultures.Option o : options) {
            ByteBufUtils.writeUTF8String(buf, o.cultureKey);
            buf.writeShort(o.villages);
            buf.writeInt(o.nearestDistance);
            buf.writeInt(o.freeUnits);
            buf.writeByte(o.chancePercent);
            buf.writeByte(o.verdict.ordinal());
        }
        MsgRecruitPreviewRequest.writeWanted(buf, wanted);
        buf.writeShort(stops.size());
        for (RecruitPlan.Stop st : stops) {
            buf.writeLong(st.center.toLong());
            ByteBufUtils.writeUTF8String(buf, st.cultureKey);
        }
        buf.writeInt(travelFee);
        buf.writeInt(purse);
    }

    public static class Handler implements IMessageHandler<MsgRecruitPreview, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitPreview msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> handle(msg));
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void handle(MsgRecruitPreview msg) {
            if (Minecraft.getMinecraft().currentScreen instanceof GuiWorkerRecruitCultures) {
                ((GuiWorkerRecruitCultures) Minecraft.getMinecraft().currentScreen).receivePreview(msg);
            }
        }
    }
}
