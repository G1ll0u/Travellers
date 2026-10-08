package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.debug.path.PathExporter;
import com.jubitus.travellers.debug.path.PathMarkerViz;
import com.jubitus.travellers.debug.path.PathTestManager;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug path …`. */
public class PathDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "path";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executePathDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "pos1", "pos2", "mark", "clearmarks",
                    "save", "load", "test", "stop", "cancel", "show", "export");
        }
        if (args.length != 3) return Collections.emptyList();
        String sub = args[1].toLowerCase();
        if (sub.equals("test")) return getListOfStringsMatchingLastWord(args, "--mount", "--waypoints");
        if (sub.equals("show")) return getListOfStringsMatchingLastWord(args, "--marks", "--trace");
        if ((sub.equals("load") || sub.equals("save")) && sender instanceof EntityPlayerMP) {
            return getListOfStringsMatchingLastWord(args, PathTestManager.savedRouteNames((EntityPlayerMP) sender));
        }
        return Collections.emptyList();
    }

    /**
     * {@code /travellersdebug path pos1|pos2|mark|clearmarks|test [--mount] [--waypoints]|stop|cancel|show|export}
     * - see {@link PathTestManager} for the per-player state and {@link PathExporter} for the file
     * format. Everything here is throwaway debug tooling; it never touches the travel AI itself.
     */
    private void executePathDebug(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";

        switch (sub) {
            case "pos1": {
                BlockPos p = resolveTargetedPos(player);
                PathTestManager.setPos1(player, p);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + "path pos1 = " + PathTestManager.posString(p)));
                return;
            }
            case "pos2": {
                BlockPos p = resolveTargetedPos(player);
                PathTestManager.setPos2(player, p);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + "path pos2 = " + PathTestManager.posString(p)));
                return;
            }
            case "mark": {
                BlockPos p = resolveTargetedPos(player);
                PathTestManager.addMark(player, p);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + "Marked point #" + PathTestManager.state(player).marks.size()
                        + " at " + PathTestManager.posString(p)));
                return;
            }
            case "clearmarks": {
                int n = PathTestManager.state(player).marks.size();
                PathTestManager.clearMarks(player);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Cleared " + n + " mark(s)."));
                return;
            }
            case "save": {
                String name = args.length >= 3 ? args[2] : null;
                String status = PathTestManager.saveRoute(player, name);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + status));
                return;
            }
            case "load": {
                String name = args.length >= 3 ? args[2] : null;
                String status = PathTestManager.loadRoute(player, name);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + status));
                return;
            }
            case "test": {
                boolean mount = false;
                boolean forceWaypoints = false;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("--mount")) mount = true;
                    if (args[i].equalsIgnoreCase("--waypoints")) forceWaypoints = true;
                }
                String status = PathTestManager.startTest(player, mount, forceWaypoints);
                sendSummary(sender, status);
                return;
            }
            case "stop":
            case "cancel": {
                String summary = PathTestManager.stopTest(player);
                if (summary == null) {
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No path test running."));
                } else {
                    sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Path test stopped."));
                    sendSummary(sender, summary);
                }
                return;
            }
            case "show": {
                executePathShow(player, args);
                return;
            }
            case "export": {
                if (args.length < 3) throw new CommandException("Usage: /travellersdebug path export <name> [traceFile]");
                String traceOverride = args.length >= 4 ? args[3] : null;
                String file = PathExporter.export(player, args[2], traceOverride);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + "Path comparison exported to: " + TextFormatting.YELLOW + file));
                return;
            }
            default:
                throw new CommandException("Usage: /travellersdebug path "
                        + "pos1|pos2|mark|clearmarks|save [name]|load [name]|test [--mount] [--waypoints]|stop|cancel|show --marks|show --trace <file>|export <name>");
        }
    }

    private void executePathShow(EntityPlayerMP player, String[] args) throws CommandException {
        String flag = args.length >= 3 ? args[2].toLowerCase() : "--marks";
        World world = player.world;

        if (flag.equals("--marks")) {
            List<BlockPos> marks = PathTestManager.state(player).marks;
            if (marks.isEmpty()) throw new CommandException("No marks - use 'path mark' while walking the ideal route.");
            for (BlockPos p : marks) {
                PathMarkerViz.mark(world, p, EnumParticleTypes.VILLAGER_HAPPY);
            }
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Shown " + marks.size() + " mark(s)."));
            return;
        }

        if (flag.equals("--trace")) {
            if (args.length < 4) throw new CommandException("Usage: /travellersdebug path show --trace <file>");
            List<Vec3d> points = PathExporter.readTracePositions(player.getServer(), args[3]);
            if (points.isEmpty()) throw new CommandException("No positions found in that trace file.");
            int shown = 0;
            for (Vec3d p : points) {
                PathMarkerViz.mark(world, p.add(0, 1.1, 0), EnumParticleTypes.FLAME);
                shown++;
            }
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Shown " + shown + " trace point(s)."));
            return;
        }

        throw new CommandException("Usage: /travellersdebug path show --marks|--trace <file>");
    }

    /** The block the player is looking at within 200 blocks, or their own feet if nothing is hit. */
    private BlockPos resolveTargetedPos(EntityPlayerMP player) {
        Vec3d eye = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        Vec3d far = eye.add(look.scale(200.0D));
        RayTraceResult hit = player.world.rayTraceBlocks(eye, far, false, true, false);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return player.getPosition();
    }
}
