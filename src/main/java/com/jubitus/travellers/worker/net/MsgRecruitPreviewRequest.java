package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.worker.RecruitCultures;
import com.jubitus.travellers.worker.RecruitPlan;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** The recruit screen's wanted counts per culture; the server answers with {@link MsgRecruitPreview}. */
public class MsgRecruitPreviewRequest implements IMessage {

    private int workerId;
    private Map<String, Integer> wanted = new LinkedHashMap<>();

    public MsgRecruitPreviewRequest() {
    }

    public MsgRecruitPreviewRequest(int workerId, Map<String, Integer> wanted) {
        this.workerId = workerId;
        this.wanted = wanted;
    }

    static void writeWanted(ByteBuf buf, Map<String, Integer> wanted) {
        buf.writeShort(wanted.size());
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, e.getKey());
            buf.writeShort(e.getValue());
        }
    }

    static Map<String, Integer> readWanted(ByteBuf buf) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int n = Math.min(buf.readShort(), 64);
        for (int i = 0; i < n; i++) out.put(ByteBufUtils.readUTF8String(buf), (int) buf.readShort());
        return out;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        wanted = readWanted(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        writeWanted(buf, wanted);
    }

    public static class Handler implements IMessageHandler<MsgRecruitPreviewRequest, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitPreviewRequest msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                EntityTravellerWorker worker = WorkerLookup.ownedById(player, msg.workerId);
                if (worker == null) return;
                RecruitPlan plan = RecruitPlan.build(player.getServerWorld(), player.getUniqueID(), player.getPosition(), msg.wanted);
                TravellersNetwork.sendToPlayer(player, new MsgRecruitPreview(msg.workerId,
                        RecruitCultures.around(player.getServerWorld(), player.getUniqueID(), player.getPosition()), plan));
            });
            return null;
        }
    }
}
