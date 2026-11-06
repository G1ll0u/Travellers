package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.Collections;
import java.util.List;

import static net.minecraft.command.CommandBase.getListOfStringsMatchingLastWord;
import static net.minecraft.command.CommandBase.parseInt;

/** `/travellersdebug drunk …`: makes the looked-at unit tipsy (as after sips of a drink this strong) or sober. */
public class DrunkDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "drunk";
    }

    @Override
    public String description() {
        return "Make the looked-at unit tipsy, or sober it up";
    }

    @Override
    public String usage() {
        return "sip <strength 1-10> [sips] | sober";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) return false;
        EntityTraveller unit;
        switch (args[1]) {
            case "sober":
                if (args.length != 2) return false;
                unit = DebugCommandSupport.resolveTraceTarget(sender, args, args.length);
                unit.drunk.setSoberAt(0L);
                say(sender, unit.getName() + " is sober.");
                return true;
            case "sip":
                if (args.length != 3 && args.length != 4) return false;
                int strength = parseInt(args[2], 1, 10);
                int sips = args.length == 4 ? parseInt(args[3], 1, 10) : 1;
                unit = DebugCommandSupport.resolveTraceTarget(sender, args, args.length);
                for (int i = 0; i < sips; i++) unit.drunk.sip(strength);
                long left = unit.drunk.getSoberAt() - unit.world.getTotalWorldTime();
                say(sender, unit.drunk.isDrunk() ? unit.getName() + " sobers up in " + Math.max(0L, left) / 20 + "s."
                        : "Drunkenness is off in the config.");
                return true;
            default:
                return false;
        }
    }

    private static void say(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + text));
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, "sip", "sober");
    }
}
