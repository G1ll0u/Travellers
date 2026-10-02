package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MsgOpenFormationHubGui implements IMessage {

    private int representativeEntityId;
    private boolean standalone;
    // When true the client only refreshes an already-open Hub in place and never opens one - used
    // by WorkerGroupService's post-operation push so it can't reopen a Hub the player just closed.
    private boolean updateOnly;
    private NBTTagCompound initialScopeTag;
    private NBTTagCompound leadersWrapper;   // contains NBTTagList "L"
    private NBTTagCompound settingsWrapper;  // contains NBTTagList "S"

    public MsgOpenFormationHubGui() {}

    public MsgOpenFormationHubGui(int representativeEntityId,
                                   WorkerFormationScopeKey initialScope,
                                   List<FormationLeaderInfo> leaders,
                                   Map<String, WorkerFormationSettings> settingsByScope) {
        this(representativeEntityId, false, false, initialScope, leaders, settingsByScope);
    }

    public MsgOpenFormationHubGui(int representativeEntityId,
                                   boolean standalone,
                                   WorkerFormationScopeKey initialScope,
                                   List<FormationLeaderInfo> leaders,
                                   Map<String, WorkerFormationSettings> settingsByScope) {
        this(representativeEntityId, standalone, false, initialScope, leaders, settingsByScope);
    }

    public MsgOpenFormationHubGui(int representativeEntityId,
                                   boolean standalone,
                                   boolean updateOnly,
                                   WorkerFormationScopeKey initialScope,
                                   List<FormationLeaderInfo> leaders,
                                   Map<String, WorkerFormationSettings> settingsByScope) {
        this.representativeEntityId = representativeEntityId;
        this.standalone = standalone;
        this.updateOnly = updateOnly;
        this.initialScopeTag = initialScope.toNbt();

        NBTTagList lList = new NBTTagList();
        for (FormationLeaderInfo info : leaders) lList.appendTag(info.toNbt());
        this.leadersWrapper = new NBTTagCompound();
        this.leadersWrapper.setTag("L", lList);

        NBTTagList sList = new NBTTagList();
        for (Map.Entry<String, WorkerFormationSettings> e : settingsByScope.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("ScopeKey", e.getKey());
            entry.setTag("Settings", e.getValue().toNbt());
            sList.appendTag(entry);
        }
        this.settingsWrapper = new NBTTagCompound();
        this.settingsWrapper.setTag("S", sList);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        representativeEntityId = buf.readInt();
        standalone       = buf.readBoolean();
        updateOnly       = buf.readBoolean();
        initialScopeTag  = ByteBufUtils.readTag(buf);
        leadersWrapper   = ByteBufUtils.readTag(buf);
        settingsWrapper  = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(representativeEntityId);
        buf.writeBoolean(standalone);
        buf.writeBoolean(updateOnly);
        ByteBufUtils.writeTag(buf, initialScopeTag);
        ByteBufUtils.writeTag(buf, leadersWrapper  != null ? leadersWrapper  : new NBTTagCompound());
        ByteBufUtils.writeTag(buf, settingsWrapper != null ? settingsWrapper : new NBTTagCompound());
    }

    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<MsgOpenFormationHubGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenFormationHubGui msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                WorkerFormationScopeKey initialScope = WorkerFormationScopeKey.fromNbt(msg.initialScopeTag);

                List<FormationLeaderInfo> leaders = new ArrayList<>();
                if (msg.leadersWrapper != null) {
                    NBTTagList lList = msg.leadersWrapper.getTagList("L", 10);
                    for (int i = 0; i < lList.tagCount(); i++) {
                        leaders.add(FormationLeaderInfo.fromNbt(lList.getCompoundTagAt(i)));
                    }
                }

                Map<String, WorkerFormationSettings> settingsByScope = new HashMap<>();
                if (msg.settingsWrapper != null) {
                    NBTTagList sList = msg.settingsWrapper.getTagList("S", 10);
                    for (int i = 0; i < sList.tagCount(); i++) {
                        NBTTagCompound entry = sList.getCompoundTagAt(i);
                        settingsByScope.put(entry.getString("ScopeKey"),
                                WorkerFormationSettings.fromNbt(entry.getCompoundTag("Settings")));
                    }
                }

                net.minecraft.client.gui.GuiScreen current = Minecraft.getMinecraft().currentScreen;
                if (current instanceof com.jubitus.travellers.follow.gui.GuiWorkerFormationHub) {
                    // Update leaders in place — avoids flicker and re-init when refreshing after group changes
                    ((com.jubitus.travellers.follow.gui.GuiWorkerFormationHub) current)
                            .updateLeaders(leaders, settingsByScope);
                } else if (!msg.updateOnly) {
                    Minecraft.getMinecraft().displayGuiScreen(
                            new com.jubitus.travellers.follow.gui.GuiWorkerFormationHub(
                                    Minecraft.getMinecraft().player,
                                    msg.representativeEntityId,
                                    msg.standalone,
                                    leaders,
                                    settingsByScope,
                                    initialScope
                            )
                    );
                }
            });
            return null;
        }
    }
}
