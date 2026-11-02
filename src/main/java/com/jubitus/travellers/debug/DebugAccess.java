package com.jubitus.travellers.debug;

import com.jubitus.travellers.debug.cmd.DebugSubcommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

/** The one permission test for every debug message and command: operators only, except what a solo player may run. */
public final class DebugAccess {

    private DebugAccess() {
    }

    public static boolean allowed(EntityPlayerMP player) {
        return player.canUseCommand(2, "travellersdebug");
    }

    public static boolean isOp(ICommandSender sender) {
        return sender.canUseCommand(2, "travellersdebug");
    }

    /** The owner of a local (integrated-server) world; never a LAN guest. */
    public static boolean isSoloOwner(MinecraftServer server, ICommandSender sender) {
        return server.isSinglePlayer() && sender instanceof EntityPlayerMP
                && sender.getName().equalsIgnoreCase(server.getServerOwner());
    }

    public static boolean mayRun(MinecraftServer server, ICommandSender sender, DebugSubcommand sub) {
        return isOp(sender) || (!sub.opOnly() && isSoloOwner(server, sender));
    }
}
