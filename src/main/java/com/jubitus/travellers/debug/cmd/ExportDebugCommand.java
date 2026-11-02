package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.debug.DebugAccess;
import com.jubitus.travellers.debug.export.DebugExporter;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;

import java.io.IOException;

/** `/travellersdebug export`: zips the logs, traces, dumps and Travellers config for a bug report. */
public class ExportDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String description() {
        return "Zip logs and config into one file to send with a bug report";
    }

    @Override
    public String usage() {
        return "";
    }

    @Override
    public boolean opOnly() {
        return false;
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        DebugExporter.Result result;
        try {
            result = DebugExporter.export(server);
        } catch (IOException e) {
            throw new CommandException("Export failed: " + e.getMessage());
        }
        TextComponentString line = new TextComponentString(TextFormatting.GREEN + "Export written ("
                + (result.zip.length() / 1024) + " KB, " + result.fileCount + " files): "
                + TextFormatting.YELLOW + result.zip.getAbsolutePath());
        // OPEN_FILE runs on the player's machine, so it only makes sense for a local world.
        if (DebugAccess.isSoloOwner(server, sender)) {
            line.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, result.zip.getParentFile().getAbsolutePath()));
        }
        sender.sendMessage(line);
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "Send this zip with your report. latest.log contains your player name; check it if you care."));
        for (String note : result.notes) sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Note: " + note));
        return true;
    }
}
