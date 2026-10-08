package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug dialogue …`. */
public class DialogueDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "dialogue";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 3 && args[1].equalsIgnoreCase("memory") && args[2].equalsIgnoreCase("dump")) {
            executeDialogueMemoryDump(sender, args);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("memory") && args[2].equalsIgnoreCase("clear")) {
            executeDialogueMemoryClear(sender, args);
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("setup")) {
            // Temporary entry point - see MsgOpenDialogueSetupGui's class doc.
            if (sender instanceof EntityPlayerMP) {
                com.jubitus.travellers.net.TravellersNetwork.CHANNEL.sendTo(
                        new com.jubitus.travellers.dialogue.net.MsgOpenDialogueSetupGui(),
                        (EntityPlayerMP) sender);
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        // "setup" is temporary: opens GuiDialogueSetup directly, ahead of the real entry
        // point the dialogue plan's Part C builds. See MsgOpenDialogueSetupGui's class doc.
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "setup", "memory");
        if (args.length == 3 && args[1].equalsIgnoreCase("memory")) return getListOfStringsMatchingLastWord(args, "dump", "clear");
        if (args.length == 4 && args[1].equalsIgnoreCase("memory")
                && (args[2].equalsIgnoreCase("dump") || args[2].equalsIgnoreCase("clear"))) {
            return getListOfStringsMatchingLastWord(args, nearbyTravellerNames(sender));
        }
        return Collections.emptyList();
    }

    /**
     * The traveller a trace command applies to: an explicit name if given, else whichever one the
     * player is looking at, else the nearest. Looking at one is checked first because "that one, the
     * one misbehaving" is the whole point when a dozen are milling around.
     */
    /** "/travellersdebug dialogue memory dump [name]" - validation surface for Phase 3 memory, no player-facing purpose. */
    private void executeDialogueMemoryDump(ICommandSender sender, String[] args) throws CommandException {
        EntityTraveller traveller = resolveTraceTarget(sender, args, 3);
        List<String> lines = traveller.dialogueMemory.debugDumpLines();
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                + "Dialogue memory for " + traveller.getName() + ":"));
        if (lines.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  (no memories yet)"));
        } else {
            for (String line : lines) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + line));
            }
        }
    }

    /** "/travellersdebug dialogue memory clear [name]" - purges a traveller's global fact list and life
     * summary (keeps the per-player "have we met" roster), e.g. to recover from a bad extraction call. */
    private void executeDialogueMemoryClear(ICommandSender sender, String[] args) throws CommandException {
        EntityTraveller traveller = resolveTraceTarget(sender, args, 3);
        traveller.dialogueMemory.clearMemories();
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                + "Cleared dialogue memory for " + traveller.getName() + "."));
    }
}
