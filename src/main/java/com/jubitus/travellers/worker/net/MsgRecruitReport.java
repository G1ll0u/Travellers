package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.worker.RecruitReport;
import com.jubitus.travellers.worker.gui.GuiRecruitReport;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Server → owner: the recruiting report, opening the report screen or refreshing it. */
public class MsgRecruitReport implements IMessage {

    private RecruitReport report;

    public MsgRecruitReport() {
    }

    public MsgRecruitReport(RecruitReport report) {
        this.report = report;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        report = RecruitReport.read(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        report.write(buf);
    }

    public static class Handler implements IMessageHandler<MsgRecruitReport, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitReport msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> handle(msg));
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void handle(MsgRecruitReport msg) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen instanceof GuiRecruitReport && ((GuiRecruitReport) mc.currentScreen).isFor(msg.report.entityId)) {
                ((GuiRecruitReport) mc.currentScreen).update(msg.report);
            } else {
                mc.displayGuiScreen(new GuiRecruitReport(msg.report));
            }
        }
    }
}
