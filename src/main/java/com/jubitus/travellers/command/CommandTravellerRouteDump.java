package com.jubitus.travellers.command;

import com.jubitus.travellers.travel.EntityAITravel;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.pathing.MPBIntegration;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Loader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * /travellerroutedump — exports the MPB road route of the nearest traveller
 * (within 64 blocks) to config/travellers/debug/.
 *
 * Usage: /travellerroutedump [radius]
 */
public class CommandTravellerRouteDump extends CommandBase {

    @Override
    public String getName() {
        return "travellerroutedump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellerroutedump [searchRadius=64]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Player only.");
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer world = (WorldServer) player.world;
        BlockPos playerPos = player.getPosition();

        int radius = 64;
        if (args.length >= 1) {
            try {
                radius = Math.max(4, Math.min(512, Integer.parseInt(args[0])));
            } catch (NumberFormatException e) {
                throw new CommandException("Invalid radius: " + args[0]);
            }
        }

        // Find the nearest alive traveller within radius
        AxisAlignedBB box = new AxisAlignedBB(
                playerPos.getX() - radius, playerPos.getY() - 64, playerPos.getZ() - radius,
                playerPos.getX() + radius, playerPos.getY() + 64, playerPos.getZ() + radius);

        List<EntityTraveller> nearby = world.getEntitiesWithinAABB(EntityTraveller.class, box,
                e -> e != null && e.isEntityAlive());

        if (nearby.isEmpty()) {
            throw new CommandException("No travellers found within " + radius + " blocks.");
        }

        // Pick the nearest one
        EntityTraveller target = null;
        double bestDist = Double.MAX_VALUE;
        for (EntityTraveller t : nearby) {
            double d = t.getDistanceSq(playerPos.getX(), playerPos.getY(), playerPos.getZ());
            if (d < bestDist) {
                bestDist = d;
                target = t;
            }
        }

        // Prepare output directory
        File configDir = Loader.instance().getConfigDir();
        if (configDir == null) {
            throw new CommandException("Config dir is null; cannot write.");
        }
        File debugDir = new File(configDir, "travellers/debug");
        if (!debugDir.exists() && !debugDir.mkdirs()) {
            throw new CommandException("Could not create " + debugDir.getAbsolutePath());
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File outFile = new File(debugDir, "traveller_route_" + timestamp + ".txt");

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            dump(out, target, world, playerPos);
        } catch (IOException e) {
            throw new CommandException("Write failed: " + e.getMessage());
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Route dump written: " +
                TextFormatting.YELLOW + outFile.getAbsolutePath()));
    }

    private void dump(PrintWriter out, EntityTraveller mob, WorldServer world, BlockPos playerPos) {
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        out.println("# Traveller Route Dump");
        out.println("# Timestamp: " + date);
        out.println("# Player pos: " + fmt(playerPos));
        out.println();

        // ── Entity identity ──────────────────────────────────────────────────
        out.println("== ENTITY ==");
        out.println("Type    : " + mob.getClass().getSimpleName());
        out.println("UUID    : " + mob.getUniqueID());
        out.println("EntityId: " + mob.getEntityId());
        out.println("Position: " + fmtExact(mob));
        out.println("Yaw     : " + String.format("%.1f", mob.rotationYaw));
        out.println();

        // ── Village-level route (TravelCommon) ───────────────────────────────
        java.util.List<BlockPos> villageRoute = mob.getVillageRoute();
        int routeIdx = mob.getVillageRouteIndex();
        out.println("== VILLAGE ROUTE (TravelCommon) ==");
        out.println("Village stops : " + villageRoute.size());
        out.println("Route index   : " + routeIdx);
        if (!villageRoute.isEmpty()) {
            for (int i = 0; i < villageRoute.size(); i++) {
                BlockPos v = villageRoute.get(i);
                String marker = (i == routeIdx) ? " ◄ CURRENT" : "";
                out.println(String.format("  [%d] %s%s", i, fmt(v), marker));
            }
        } else {
            out.println("  (no village route)");
        }
        out.println("Target village: " + fmtNullable(mob.getTravelCommon().getTargetVillage()));
        out.println("Current wp    : " + fmtNullable(mob.getTravelCommon().getCurrentWaypoint()));
        out.println("Idling        : " + mob.isIdlingAtStop());
        out.println();

        // ── Itinerary (current leg) ──────────────────────────────────────────
        out.println("== ITINERARY (current leg) ==");
        com.jubitus.travellers.pathing.itinerary.ItineraryState st = mob.getItineraryState();
        com.jubitus.travellers.pathing.itinerary.Itinerary it = st.get();
        out.println("Itinerary-driven: " + mob.isItineraryDriven());
        if (it == null) {
            out.println("  (no itinerary)");
        } else {
            st.cursor.advance(it, mob.posX, mob.posZ);
            out.println("Source        : " + it.source + (st.isForced() ? " (forced)" : ""));
            out.println("Destination   : " + fmt(it.dest()) + (st.isFor(mob.getTravelCommon().getTargetVillage()) ? "" : "  (NOT the current target)"));
            out.println("Vertices      : " + it.size() + "  " + it.kindsSummary());
            out.println("Length        : " + (int) it.totalLength() + " blocks");
            out.println("Cursor        : seg " + st.cursor.segment() + ", " + (int) st.cursor.along()
                    + " blocks along, " + String.format("%.1f", st.cursor.offRouteDistance()) + " off route");
            out.println("Bridges left  : " + st.hasBacklog());
            out.println();
            out.println("# idx |     X |  Y |     Z | next segment");
            for (int i = 0; i < it.size(); i++) {
                String kind = i < it.size() - 1 ? (it.kind(i) == com.jubitus.travellers.pathing.itinerary.Itinerary.ROAD ? "ROAD"
                        : it.kind(i) == com.jubitus.travellers.pathing.itinerary.Itinerary.BOAT ? "BOAT"
                        : it.kind(i) == com.jubitus.travellers.pathing.itinerary.Itinerary.GATE ? "GATE" : "WALK") : "";
                String note = i == st.cursor.segment() ? " ◄ CURSOR" : "";
                out.println(String.format("%5d | %6d | %3d | %6d | %s%s", i, it.x(i), it.y(i), it.z(i), kind, note));
            }
        }
        out.println();

        // ── Re-plan the route fresh (for comparison) ──────────────────────────
        out.println("== MPB ROAD ROUTE (fresh re-plan) ==");
        if (!MPBIntegration.PRESENT) {
            out.println("  MPB not present.");
        } else {
            BlockPos from = mob.getPosition();
            BlockPos to   = mob.getTravelCommon().getTargetVillage();
            if (to == null) {
                out.println("  No target village — cannot plan route.");
            } else {
                out.println("From: " + fmt(from));
                out.println("To  : " + fmt(to));
                List<BlockPos> planned = MPBIntegration.findRoadRoute(world,
                        from.getX(), from.getZ(), to.getX(), to.getZ(), 256);
                out.println("Waypoints: " + planned.size());
                if (planned.isEmpty()) {
                    out.println("  (empty — no road route found between these points)");
                } else {
                    out.println();
                    out.println("# idx |     X |  Y |     Z");
                    for (int i = 0; i < planned.size(); i++) {
                        BlockPos wp = planned.get(i);
                        out.println(String.format("%5d | %6d | %3d | %6d",
                                i, wp.getX(), wp.getY(), wp.getZ()));
                    }
                }
            }
        }
        out.println();
        out.println("# End of dump");
    }

    private static String fmt(BlockPos p) {
        return p == null ? "null" : p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static String fmtNullable(BlockPos p) {
        return p == null ? "null" : fmt(p);
    }

    private static String fmtExact(EntityTraveller mob) {
        return String.format("%.2f %.2f %.2f", mob.posX, mob.posY, mob.posZ);
    }
}
