package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.flight.ElytraCapability;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.items.ItemHandlerHelper;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug elytra …`. */
public class ElytraDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "elytra";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeElytraDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "give", "status");
        return Collections.emptyList();
    }

    /**
     * {@code /travellersdebug elytra [distance]} - equips the nearest traveller with an elytra if it
     * has none and launches it toward a point {@code distance} blocks along the player's line of
     * sight. Stand on a cliff, look across the valley, run it.
     */
    private void executeElytraDebug(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        if (sub.equals("give")) {
            executeElytraGive(sender, player, args);
            return;
        }
        if (sub.equals("status")) {
            executeElytraStatus(sender, player);
            return;
        }

        double distance = args.length >= 2 ? parseDouble(args[1], 8.0D, 512.0D) : 96.0D;

        EntityTraveller closest = null;
        double bestSq = Double.MAX_VALUE;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(48.0D, 32.0D, 48.0D),
                t -> t != null && t.isEntityAlive())) {
            double dSq = t.getDistanceSq(player);
            if (dSq < bestSq) {
                bestSq = dSq;
                closest = t;
            }
        }

        if (closest == null) {
            throw new CommandException("No traveller within 48 blocks.");
        }

        if (!ElytraCapability.hasUsableElytra(closest)) {
            closest.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Equipped an elytra on " + closest.getName() + "."));
        }

        Vec3d look = player.getLookVec();
        Vec3d destination = new Vec3d(
                closest.posX + look.x * distance,
                closest.posY + look.y * distance,
                closest.posZ + look.z * distance);

        if (closest.elytraFlight.requestTakeoff(destination)) {
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + closest.getName()
                    + " taking off toward " + (int) destination.x + "/" + (int) destination.y + "/" + (int) destination.z + "."));
        } else {
            String why = !com.jubitus.travellers.config.TravellersConfig.ELYTRA.enabled ? "elytra flight disabled in config"
                    : closest.elytraFlight.isActive() ? "already flying"
                    : closest.elytraFlight.isOnCooldown() ? "still on landing cooldown"
                    : closest.getAttackTarget() != null || closest.isInCombat() ? "in combat"
                    : "not in a state where it can fly";
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Takeoff refused: " + why + "."));
        }
    }

    /**
     * Equip nearby travellers and leave them alone, so their own takeoff decisions can be watched.
     * "rockets" (in either argument position) also tops up every hired worker's personal inventory
     * with a stack of fireworks, for testing {@code ElytraRocketBoost} without having to hand them out
     * one by one.
     */
    private void executeElytraGive(ICommandSender sender, EntityPlayerMP player, String[] args) throws CommandException {
        boolean giveRockets = false;
        Double radiusArg = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("rockets")) {
                giveRockets = true;
            } else if (radiusArg == null) {
                radiusArg = parseDouble(args[i], 4.0D, 256.0D);
            }
        }
        double radius = radiusArg != null ? radiusArg : 48.0D;

        int equipped = 0;
        int stocked = 0;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(radius, 64.0D, radius),
                e -> e != null && e.isEntityAlive())) {
            if (!ElytraCapability.hasUsableElytra(t)) {
                t.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
                equipped++;
            }

            if (giveRockets && t instanceof EntityTravellerWorker) {
                EntityTravellerWorker worker = (EntityTravellerWorker) t;
                if (worker.isHiredFollower()) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                            worker.getPersonalInventory(), new ItemStack(Items.FIREWORKS, 64), false);
                    if (remainder.getCount() < 64) stocked++;
                }
            }
        }

        StringBuilder message = new StringBuilder(TextFormatting.GREEN.toString()).append("Equipped ").append(equipped)
                .append(" traveller(s) within ").append((int) radius).append(" blocks.");
        if (giveRockets) {
            message.append(" Gave fireworks to ").append(stocked).append(" hired worker(s).");
        }
        message.append(" They will glide on their own when the terrain suits it.");

        sender.sendMessage(new TextComponentString(message.toString()));
    }

    // --- Mount test ---

    private void executeElytraStatus(ICommandSender sender, EntityPlayerMP player) {
        int listed = 0;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(96.0D, 96.0D, 96.0D),
                t -> t != null && t.isEntityAlive() && ElytraCapability.hasUsableElytra(t))) {

            String state = t.elytraFlight.getState().name();
            if (t.elytraFlight.isOnCooldown() && !t.elytraFlight.isActive()) state += " (cooldown)";
            Vec3d flightTarget = t.elytraFlight.getTarget();

            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + t.getName()
                    + TextFormatting.WHITE + " " + state
                    + TextFormatting.DARK_GRAY + " at " + (int) t.posX + "/" + (int) t.posY + "/" + (int) t.posZ
                    + (flightTarget == null ? "" : " -> " + (int) flightTarget.x + "/" + (int) flightTarget.y + "/" + (int) flightTarget.z)));
            listed++;
        }

        if (listed == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "No elytra-wearing travellers within 96 blocks. Use '/travellersdebug elytra give' first."));
        }
    }

    // --- Sound stress test ---
}
