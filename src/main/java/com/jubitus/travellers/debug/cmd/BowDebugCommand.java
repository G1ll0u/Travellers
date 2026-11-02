package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug bow …`. */
public class BowDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "bow";
    }

    @Override
    public String description() {
        return "Archery debug";
    }

    @Override
    public String usage() {
        return "status";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeBowStatus(sender);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "status");
        return Collections.emptyList();
    }

    /** Flight state of every elytra wearer nearby - who is flying, who is on cooldown, who is walking. */
    /**
     * Ground-truth dump of the vanilla bow-draw state (isHandActive/activeItemStack/getItemInUseCount)
     * for every nearby traveller currently running the ranged AI - diagnostic for the "bow item never
     * visually shows pulled/nocked" issue. Server-authoritative values only: if these read correctly
     * here but the client still shows a resting bow, the bug is client-side (sync/render), not in our
     * AI; if they read wrong here, it's in our own tick logic.
     */
    private void executeBowStatus(ICommandSender sender) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        int listed = 0;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(48.0D, 32.0D, 48.0D),
                t -> t != null && t.isEntityAlive() && t.isAimingBow())) {

            ItemStack active = t.getActiveItemStack();
            ItemStack mainhand = t.getHeldItemMainhand();
            int inUse = t.getItemInUseCount();
            float pull = active.isEmpty() ? -1f : (active.getMaxItemUseDuration() - inUse) / 20.0f;

            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + t.getName()
                    + TextFormatting.WHITE + " isHandActive=" + t.isHandActive()
                    + " activeHand=" + t.getActiveHand()
                    + " primaryHand=" + t.getPrimaryHand()));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY + "  active="
                    + (active.isEmpty() ? "EMPTY" : active.getItem().getRegistryName())
                    + " inUseCount=" + inUse + " pull=" + pull
                    + " ghost=" + t.equipment.isGhostBow(active)));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY + "  mainhand="
                    + (mainhand.isEmpty() ? "EMPTY" : mainhand.getItem().getRegistryName())
                    + " sameRefAsActive=" + (mainhand == active)
                    + " ghost=" + t.equipment.isGhostBow(mainhand)));
            listed++;
        }

        if (listed == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "No traveller within 48 blocks is currently aiming a bow."));
        }
    }
}
