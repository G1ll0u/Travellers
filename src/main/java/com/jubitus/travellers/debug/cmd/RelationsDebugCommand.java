package com.jubitus.travellers.debug.cmd;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.io.*;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug relations …`. */
public class RelationsDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "relations";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeRelationsDebug(sender, args);
        return true;
    }

    /** {@code /travellersdebug relations <hx> <hz> <dx> <dz>} - raw Millenaire relation + bucket name. */
    private void executeRelationsDebug(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Must run on the server.");
        if (args.length < 5) {
            throw new CommandException("Usage: /travellersdebug relations <homeX> <homeZ> <destX> <destZ>");
        }
        int hx = (int) Math.floor(parseCoord(sender, args[1], true));
        int hz = (int) Math.floor(parseCoord(sender, args[2], false));
        int dx = (int) Math.floor(parseCoord(sender, args[3], true));
        int dz = (int) Math.floor(parseCoord(sender, args[4], false));
        Integer rel = com.jubitus.travellers.village.MillRelations.getRelation(
                w, new net.minecraft.util.math.BlockPos(hx, 0, hz),
                new net.minecraft.util.math.BlockPos(dx, 0, dz), w.getTotalWorldTime());
        String bucket;
        if (rel == null) {
            bucket = "unknown/not-loaded (treated as neutral)";
        } else if (rel <= -90) bucket = "OPEN CONFLICT";
        else if (rel <= -50) bucket = "very bad";
        else if (rel < 0) bucket = "poor";
        else if (rel == 0) bucket = "neutral";
        else if (rel < 50) bucket = "fair";
        else if (rel < 90) bucket = "good";
        else bucket = "excellent";
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Relation " + hx + "," + hz
                + " -> " + dx + "," + dz + TextFormatting.WHITE + ": "
                + (rel == null ? "null" : rel.toString()) + TextFormatting.GRAY + " (" + bucket + ")"));
    }
}
