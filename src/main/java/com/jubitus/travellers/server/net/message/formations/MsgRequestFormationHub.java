package com.jubitus.travellers.server.net.message.formations;

import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MsgRequestFormationHub implements IMessage {

    public MsgRequestFormationHub() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestFormationHub, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestFormationHub msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                MinecraftServer srv = player.getServer();
                if (srv == null) return;

                WorldServer overworld = srv.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                UUID ownerId = player.getUniqueID();
                WorkerFormationSavedData fsd = WorkerFormationSavedData.get(player.world);
                Map<String, WorkerFormationSettings> settingsByScope = new HashMap<>();
                List<FormationLeaderInfo> leaders = new ArrayList<>();

                int realDirectCount = WorkerFormationScopeResolver.countDirectFollowersForPlayer(srv, ownerId);
                int virtualDirectCount = 0;
                for (VirtualTravellerRecord vr : vwm.getRecordsView()) {
                    if (ownerId.equals(vr.followOwnerUUID) && vr.escortLeaderUUID == null && vr.isHiredFollower)
                        virtualDirectCount++;
                }

                WorkerFormationScopeKey playerScope = new WorkerFormationScopeKey(
                        WorkerFormationScopeKey.Type.PLAYER, ownerId);
                leaders.add(new FormationLeaderInfo(null, player.getName(),
                        realDirectCount + virtualDirectCount,
                        player.posX, player.posZ, true, false));
                settingsByScope.put(playerScope.asMapKey(), fsd.getSettings(playerScope));

                int representativeId = -1;
                for (WorldServer ws : srv.worlds) {
                    List<EntityTravellerWorker> realLeaders = ws.getEntities(EntityTravellerWorker.class, w ->
                            w != null && !w.isDead && w.isHiredFollower()
                                    && ownerId.equals(w.getFollowOwnerId())
                                    && w.getLeaderFollowerCount() > 0);
                    for (EntityTravellerWorker ldr : realLeaders) {
                        if (representativeId < 0) representativeId = ldr.getEntityId();
                        WorkerFormationScopeKey ldrScope = new WorkerFormationScopeKey(
                                WorkerFormationScopeKey.Type.WORKER, ldr.getUniqueID());
                        int fc = ldr.getLeaderFollowerCount();
                        for (VirtualTravellerRecord vr : vwm.getRecordsView()) {
                            if (ownerId.equals(vr.followOwnerUUID) && ldr.getUniqueID().equals(vr.escortLeaderUUID))
                                fc++;
                        }
                        FormationLeaderInfo info = new FormationLeaderInfo(ldr.getUniqueID(), ldr.getName(), fc,
                                ldr.posX, ldr.posZ, false, false);
                        info.cultureId = ldr.getCultureId();
                        info.textureIndex = ldr.getTextureIndex();
                        info.ownerColor = ldr.getOwnerMarkColor();
                        info.entityId = ldr.getEntityId();
                        leaders.add(info);
                        settingsByScope.put(ldrScope.asMapKey(), fsd.getSettings(ldrScope));
                    }
                }

                List<FormationLeaderInfo> virtualLeaders = vwm.buildLeaderList(ownerId, player.posX, player.posZ, overworld);
                for (FormationLeaderInfo vl : virtualLeaders) {
                    boolean alreadyAdded = leaders.stream().anyMatch(l -> l.uuid != null && l.uuid.equals(vl.uuid));
                    if (!alreadyAdded) {
                        leaders.add(vl);
                        WorkerFormationScopeKey vScope = new WorkerFormationScopeKey(
                                WorkerFormationScopeKey.Type.WORKER, vl.uuid);
                        settingsByScope.put(vScope.asMapKey(), fsd.getSettings(vScope));
                    }
                }

                leaders.sort((a, b) -> {
                    if (a.isPlayer) return -1;
                    if (b.isPlayer) return 1;
                    double da = Math.sqrt(Math.pow(a.posX - player.posX, 2) + Math.pow(a.posZ - player.posZ, 2));
                    double db = Math.sqrt(Math.pow(b.posX - player.posX, 2) + Math.pow(b.posZ - player.posZ, 2));
                    return Double.compare(da, db);
                });

                WorkerFormationScopeKey initialScope = playerScope;
                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgOpenFormationHubGui(representativeId, true, initialScope, leaders, settingsByScope),
                        player
                );
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgRequestFormationHub, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestFormationHub msg, MessageContext ctx) { return null; }
    }
}
