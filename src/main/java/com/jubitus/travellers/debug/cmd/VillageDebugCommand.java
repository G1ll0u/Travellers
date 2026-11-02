package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import javax.annotation.Nullable;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug village …`. */
public class VillageDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "village";
    }

    @Override
    public String description() {
        return "Village visit tools";
    }

    @Override
    public String usage() {
        return "show|hide|here|needs|visit <name>";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeVillageDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "show", "hide", "here", "visit", "needs");
        return Collections.emptyList();
    }

    private void executeVillageDebug(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Player only.");
        EntityPlayerMP player = (EntityPlayerMP) sender;
        String sub = args.length >= 2 ? args[1].toLowerCase() : "show";

        if (sub.equals("hide")) {
            com.jubitus.travellers.debug.village.VillageDebugView.hide(player);
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Village debug view off."));
            return;
        }
        if (sub.equals("show")) {
            com.jubitus.travellers.debug.village.VillageDebugView.show(player);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Village debug view on ("
                    + com.jubitus.travellers.village.MillFootprints.count(player.world)
                    + " Millénaire buildings known). "
                    + TextFormatting.GOLD + "orange" + TextFormatting.RESET + " = building, "
                    + TextFormatting.RED + "red" + TextFormatting.RESET + " = town hall, "
                    + TextFormatting.GREEN + "green" + TextFormatting.RESET + " = entrance, "
                    + TextFormatting.AQUA + "cyan" + TextFormatting.RESET + " = leisure, "
                    + TextFormatting.YELLOW + "yellow" + TextFormatting.RESET + " = selling, "
                    + TextFormatting.LIGHT_PURPLE + "purple" + TextFormatting.RESET + " = bed, "
                    + TextFormatting.WHITE + "white" + TextFormatting.RESET + " = panel."));
            return;
        }
        if (sub.equals("here")) {
            com.jubitus.travellers.village.MillFootprints.Footprint f =
                    com.jubitus.travellers.village.MillFootprints.at(player.world, player.posX, player.posY, player.posZ, 0);
            if (f == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                        + "Not inside any Millénaire building (roof and walls included)."));
                com.jubitus.travellers.village.MillFootprints.Footprint nearest = null;
                double best = Double.MAX_VALUE;
                for (com.jubitus.travellers.village.MillFootprints.Footprint c
                        : com.jubitus.travellers.village.MillFootprints.near(player.world, player.posX, player.posZ, 48)) {
                    double dx = (c.minX + c.maxX + 1) * 0.5D - player.posX, dz = (c.minZ + c.maxZ + 1) * 0.5D - player.posZ;
                    if (dx * dx + dz * dz < best) {
                        best = dx * dx + dz * dz;
                        nearest = c;
                    }
                }
                if (nearest != null) sendFootprintDiag(sender, nearest, player);
                return;
            }
            sendFootprintDiag(sender, f, player);
            String tags;
            try {
                tags = String.join(", ", f.building.getTags());
            } catch (Throwable t) {
                tags = "?";
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "Inside: " + f.planKey()
                    + (f.building.isTownhall ? " (town hall)" : "")
                    + TextFormatting.RESET + " x " + f.minX + ".." + f.maxX
                    + " y " + f.minY + ".." + f.maxY + " z " + f.minZ + ".." + f.maxZ
                    + TextFormatting.GRAY + " tags: " + (tags.isEmpty() ? "-" : tags)));
            return;
        }
        if (sub.equals("visit")) {
            if (args.length < 3) throw new CommandException("Usage: /travellersdebug village visit <name>");
            describeVisit(sender, (WorldServer) player.world, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            return;
        }
        if (sub.equals("needs")) {
            describeNeeds(sender, (WorldServer) player.world, player.getPosition());
            return;
        }
        throw new CommandException("Usage: /travellersdebug village show|hide|here|needs|visit <name>");
    }

    /** Raw Millénaire placement data of a building next to the box we derive from it, to check the two agree. */
    private static void sendFootprintDiag(ICommandSender sender, com.jubitus.travellers.village.MillFootprints.Footprint f,
                                          EntityPlayerMP player) {
        org.millenaire.common.village.BuildingLocation loc = f.building.location;
        org.millenaire.common.buildingplan.BuildingPlan plan = loc.getPlan();
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "[diag] " + f.planKey()
                + " pos " + loc.pos + " orient " + loc.orientation + " level " + loc.level
                + " loc LxW " + loc.length + "x" + loc.width
                + (plan == null ? " plan=null" : " plan LxW " + plan.length + "x" + plan.width
                + " off " + plan.lengthOffset + "/" + plan.widthOffset + " startLevel " + plan.startLevel
                + " floors " + plan.nbfloors)));
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "[diag] mill x " + loc.minx + ".." + loc.maxx
                + " z " + loc.minz + ".." + loc.maxz + " y " + loc.miny + ".." + loc.maxy
                + " | ours x " + f.minX + ".." + f.maxX + " z " + f.minZ + ".." + f.maxZ + " y " + f.minY + ".." + f.maxY
                + " | you " + (int) Math.floor(player.posX) + "," + (int) Math.floor(player.posY) + "," + (int) Math.floor(player.posZ)));

        // Ground truth: bounding box of built blocks around our box (loaded ground only, above the floor row).
        int pad = 4, bx0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE, bz0 = Integer.MAX_VALUE, bz1 = Integer.MIN_VALUE;
        net.minecraft.util.math.BlockPos.MutableBlockPos mp = new net.minecraft.util.math.BlockPos.MutableBlockPos();
        for (int x = f.minX - pad; x <= f.maxX + pad; x++) {
            for (int z = f.minZ - pad; z <= f.maxZ + pad; z++) {
                if (!player.world.isBlockLoaded(mp.setPos(x, f.minY, z))) continue;
                for (int y = f.minY + 1; y <= f.maxY + 2; y++) {
                    if (!com.jubitus.travellers.ground.BlockRules.isBuilt(player.world.getBlockState(mp.setPos(x, y, z)))) continue;
                    bx0 = Math.min(bx0, x);
                    bx1 = Math.max(bx1, x);
                    bz0 = Math.min(bz0, z);
                    bz1 = Math.max(bz1, z);
                    break;
                }
            }
        }
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "[diag] built blocks x " + bx0 + ".." + bx1
                + " z " + bz0 + ".." + bz1 + " (searched " + pad + " blocks around)"));
    }

    /** What the nearest settlement still needs for its constructions, and what it holds already. */
    private void describeNeeds(ICommandSender sender, WorldServer world, net.minecraft.util.math.BlockPos pos) {
        com.jubitus.travellers.village.MillSettlements.Settlement s =
                com.jubitus.travellers.village.MillSettlements.nearest(world, pos, 256);
        org.millenaire.common.village.Building th = s == null ? null : s.townHall(world);
        if (th == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No Millénaire settlement within 256 blocks."));
            return;
        }
        java.util.List<String> lines = com.jubitus.travellers.village.MillGoods.describeNeeds(world, th);
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + s.displayName() + TextFormatting.RESET
                + " pop " + s.population(world) + ", goal " + th.buildingGoal + ", "
                + lines.size() + " construction item(s) needed"));
        for (String line : lines) sender.sendMessage(new TextComponentString("  " + line));
    }

    /** Where a unit is in its village stay (loaded or offscreen), and where its horse is. */
    private void describeVisit(ICommandSender sender, WorldServer world, String name) {
        String needle = name.toLowerCase(java.util.Locale.ROOT);
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            if (!(e instanceof com.jubitus.travellers.unit.EntityTraveller) || e.isDead) continue;
            if (!e.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            com.jubitus.travellers.unit.EntityTraveller t = (com.jubitus.travellers.unit.EntityTraveller) e;
            String horse;
            if (t.getMountUUID() == null) horse = "none";
            else if (t.mountState.getPendingMountNbt() != null) horse = "held as data, post " + fmtPos(t.mountState.getPendingMountPos());
            else {
                net.minecraft.entity.Entity h = world.getEntityFromUuid(t.getMountUUID());
                horse = h == null ? "not loaded" : "live at " + fmtPos(h.getPosition()) + " (" + t.mountState.getMountMode() + ")";
            }
            sendVisit(sender, "LOADED " + t.getName(), t.getStayState(), horse);
            return;
        }
        for (com.jubitus.travellers.virtual.VirtualTravellerRecord r
                : com.jubitus.travellers.virtual.VirtualTravellerManager.get(world).getAllRecords()) {
            if (r.name == null || !r.name.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            String horse = r.mountNbt != null ? "data on the record (" + r.mountMode + ")"
                    : r.visit != null && r.visit.horseAtPost ? "live at its post" : r.mounted ? "bonded, not held" : "none";
            sendVisit(sender, "VIRTUAL " + r.name + " at " + (int) r.posX + "," + (int) r.posZ, r.visit, horse);
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No traveller named like '" + name + "'."));
    }

    private static void sendVisit(ICommandSender sender, String who,
                                  @javax.annotation.Nullable com.jubitus.travellers.stay.StayState st, String horse) {
        String visit = st == null ? "not visiting"
                : "stop " + fmtPos(st.stop) + (st.asFollower ? " (with group)" : "")
                + (st.isLeaving() ? " leaving" : " left=" + st.remainingTicks / 20 + "s")
                + " spot " + fmtPos(st.spot) + " post " + fmtPos(st.hitchPos);
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + who + TextFormatting.RESET + " | " + visit
                + TextFormatting.GRAY + " | horse: " + horse));
    }

    private static String fmtPos(@javax.annotation.Nullable net.minecraft.util.math.BlockPos p) {
        return p == null ? "-" : p.getX() + "," + p.getY() + "," + p.getZ();
    }

    // --- Battle debug: list / inspect / force-end / spawn a test fight ---
}
