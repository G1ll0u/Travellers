package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.entity.entityAI.travel.EntityAITravel;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.utils.world.MPBIntegration;
import com.jubitus.travellers.utils.world.MPBRouteTracker;
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
        out.println("Target village: " + fmtNullable(mob.getTargetVillage()));
        out.println("Current wp    : " + fmtNullable(mob.getCurrentWaypoint()));
        out.println("Idling        : " + mob.isVillageIdling());
        out.println();

        // ── MPB road route (in-progress) ─────────────────────────────────────
        out.println("== MPB ROAD ROUTE (active tracker) ==");
        EntityAITravel aiTravel = mob.getTravelAI();
        if (aiTravel == null) {
            out.println("  TravelAI: null (not initialized?)");
        } else {
            MPBRouteTracker.RouteSnapshot snap = aiTravel.getMPBRouteSnapshot();
            out.println("Routed to     : " + snap.toX + ", " + snap.toZ);
            out.println("Leg count     : " + snap.legs.size());
            out.println("Current leg   : " + snap.legIndex);
            out.println("Cursor (in leg): " + snap.wpIndex);
            out.println("Transit       : " + snap.transit);
            out.println("Exhausted     : " + snap.exhausted);
            out.println("Replan needed : " + snap.replanRequested);
            if (snap.legs.isEmpty()) {
                out.println("  (no MPB route — traveller uses direct nav)");
            } else {
                for (int l = 0; l < snap.legs.size(); l++) {
                    List<BlockPos> leg = snap.legs.get(l);
                    out.println();
                    out.println(String.format("-- LEG %d (%d waypoints)%s --",
                            l, leg.size(), l == snap.legIndex ? " ◄ CURRENT" : ""));
                    out.println("# idx |     X |  Y |     Z | note");
                    for (int i = 0; i < leg.size(); i++) {
                        BlockPos wp = leg.get(i);
                        String note = (l == snap.legIndex && i == snap.wpIndex) ? " ◄ CURSOR" : "";
                        out.println(String.format("%5d | %6d | %3d | %6d |%s",
                                i, wp.getX(), wp.getY(), wp.getZ(), note));
                    }
                }
            }
        }
        out.println();

        // ── Re-plan the route fresh (for comparison) ──────────────────────────
        out.println("== MPB ROAD ROUTE (fresh re-plan) ==");
        if (!MPBIntegration.PRESENT) {
            out.println("  MPB not present.");
        } else {
            BlockPos from = mob.getPosition();
            BlockPos to   = mob.getTargetVillage();
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
