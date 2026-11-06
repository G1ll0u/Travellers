package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.village.CultureSnacks;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.Collections;
import java.util.List;

import static net.minecraft.command.CommandBase.getListOfStringsMatchingLastWord;
import static net.minecraft.command.CommandBase.parseInt;

/** `/travellersdebug pose …`: seats the unit under the crosshair, facing you, drinking, for screenshots. */
public class PoseDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "pose";
    }

    @Override
    public String description() {
        return "Seat the looked-at unit drinking, frozen or in a loop, for screenshots";
    }

    @Override
    public String usage() {
        return "drink | raise <0-100> | release";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) return false;
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Run it in game, looking at the unit.");
        EntityPlayerMP player = (EntityPlayerMP) sender;
        EntityTraveller unit = DebugCommandSupport.resolveTraceTarget(sender, args, args.length);
        switch (args[1]) {
            case "release":
                unit.snack.releasePose();
                unit.sit.releaseHold();
                unit.setNoAI(false);
                say(sender, unit.getName() + " released.");
                return true;
            case "drink":
                if (args.length != 2) return false;
                seat(unit, player, false);
                say(sender, unit.getName() + " drinks in a loop. '/travellersdebug pose raise <0-100>' to freeze it.");
                return true;
            case "raise":
                if (args.length != 3) return false;
                int percent = parseInt(args[2], 0, 100);
                seat(unit, player, true);
                unit.snack.freezeAt(percent / 100.0F);
                say(sender, unit.getName() + " frozen at " + percent + "% (0 = chest, 100 = mouth).");
                return true;
            default:
                return false;
        }
    }

    // Keeps the shown item when re-posing with an empty hand, so stepping through percentages never swaps the bottle.
    private static void seat(EntityTraveller unit, EntityPlayerMP player, boolean keepItem) throws CommandException {
        float yaw = (float) (Math.atan2(player.posZ - unit.posZ, player.posX - unit.posX) * 180.0D / Math.PI) - 90.0F;
        ItemStack stack = player.getHeldItemMainhand().copy();
        if (stack.isEmpty() && keepItem && unit.sit.isHeld()) stack = unit.snack.getStack().copy();
        if (stack.isEmpty()) {
            String culture = unit.getCultureId();
            CultureSnacks.Snack drink = culture == null ? null : CultureSnacks.pick(culture, true, unit.getRNG());
            if (drink == null) throw new CommandException("Its culture has no drink: hold the item to show.");
            stack = drink.stack.copy();
        }
        stack.setCount(1);
        if (!unit.sit.hold(yaw)) {
            throw new CommandException(unit.getName() + " can't sit here (needs solid ground, out of combat, not riding).");
        }
        unit.setNoAI(true);
        unit.snack.pose(stack);
    }

    private static void say(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + text));
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, "drink", "raise", "release");
    }
}
