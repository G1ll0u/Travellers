package com.jubitus.travellers.net;

import com.jubitus.jubiconfig.network.MsgOpenConfigGui;
import com.jubitus.jubiconfig.network.MsgSaveConfigGui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.util.function.Supplier;

/** The mod's one network channel; each feature registers its messages from its own net/*Messages class. */
public final class TravellersNetwork {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("travfollow");
    private static final IMessageHandler<IMessage, IMessage> NOOP = (msg, ctx) -> null;
    private static boolean registered = false;
    private static int nextId = 0;

    private TravellersNetwork() {
    }

    // INVARIANT: message ids follow call order; keep this order deterministic and identical on both sides.
    public static void init() {
        if (registered) return;
        registered = true;
        com.jubitus.travellers.worker.net.WorkerMessages.register();
        com.jubitus.travellers.follow.net.FollowMessages.register();
        com.jubitus.travellers.combat.net.CombatMessages.register();
        com.jubitus.travellers.zone.net.ZoneMessages.register();
        com.jubitus.travellers.unit.equipment.net.EquipmentMessages.register();
        com.jubitus.travellers.village.net.VillageMessages.register();
        com.jubitus.travellers.worldmap.net.WorldMapMessages.register();
        com.jubitus.travellers.marker.net.MarkerMessages.register();
        com.jubitus.travellers.notify.net.NotifyMessages.register();
        com.jubitus.travellers.dialogue.net.DialogueMessages.register();
        com.jubitus.travellers.sound.net.SoundMessages.register();
        com.jubitus.travellers.choir.net.ChoirMessages.register();
        com.jubitus.travellers.debug.net.DebugMessages.register();
        toClient(MsgOpenConfigGui.class, () -> new MsgOpenConfigGui.Handler());
        toServer(MsgSaveConfigGui.class, MsgSaveConfigGui.Handler.class);
    }

    /** Client to server. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void toServer(Class<? extends IMessage> type, Class<? extends IMessageHandler> handler) {
        CHANNEL.registerMessage((Class) handler, (Class) type, nextId++, Side.SERVER);
    }

    /** Server to client; the handler is only built on a client, since it may touch client-only classes. */
    // INVARIANT: pass a lambda (() -> new X.Handler()), never X.Handler::new — a method reference loads the client class on a dedicated server.
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void toClient(Class<? extends IMessage> type, Supplier<? extends IMessageHandler> clientHandler) {
        IMessageHandler handler = FMLCommonHandler.instance().getSide().isClient() ? clientHandler.get() : NOOP;
        CHANNEL.registerMessage(handler, (Class) type, nextId++, Side.CLIENT);
    }

    public static void sendToPlayer(EntityPlayer player, IMessage msg) {
        if (player instanceof EntityPlayerMP) {
            CHANNEL.sendTo(msg, (EntityPlayerMP) player);
        }
    }
}
