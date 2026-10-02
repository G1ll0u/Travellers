package com.jubitus.travellers.command;

import com.jubitus.travellers.server.map.pyramid.MapFillStats;
import com.jubitus.travellers.ground.chunk.ChunkReader;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.culture.VoiceSet;
import com.jubitus.travellers.entity.entityai.flight.ElytraCapability;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.debug.path.PathExporter;
import com.jubitus.travellers.debug.path.PathMarkerViz;
import com.jubitus.travellers.debug.path.PathTestManager;
import com.jubitus.travellers.debug.profile.MovementProfiler;
import com.jubitus.travellers.debug.trace.TraceChannel;
import com.jubitus.travellers.debug.trace.TraceSession;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class CommandTravellerDebug extends CommandBase {

    @Override
    public String getName() {
        return "travellersdebug";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersdebug [profile start|stop|report|status|reset [workers]|camp [here|recompute|join <n> [culture] [TRAVELLER|RAIDER]|leave|reset [all]]|battle [list|info <n>|end <n>|spawnfight [x z]|loot]|map resweep [all]|map resweep cancel|map stats [reset]|elytra [distance]|elytra give [radius] [rockets]|elytra status|mount give|mount ride|mount lead|mount hitch|stable [list|dump <n>|log <n> [count]|verify <n>]|bow status|sound stress|sound rapidfire|path pos1|path pos2|path mark|path clearmarks|path save [name]|path load [name]|path test [--mount] [--waypoints]|path stop|path show --marks|path show --trace <file>|path export <name>|terrain [region] <x> <z>|terrain export [radius] [mode] [fill]|village show|hide|here|cultures|dialogue setup]";
    }

    /**
     * Full tab-completion over the whole command tree. Worth the table below rather than only
     * completing the first word: most of these subcommands are things you reach for exactly when
     * something is going wrong in front of you, and stopping to remember the exact spelling of
     * "resweep" or which of start/stop/list a tool uses is friction at the worst possible moment.
     * Live traveller names are offered for the trace commands, so an individual can be picked out of
     * a crowd by name instead of by aiming at it.
     */
    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable net.minecraft.util.math.BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "trace", "profile", "mount", "elytra", "camp", "battle",
                    "map", "reset", "bow", "sound", "path", "cultures", "stable", "equip", "terrain",
                    "destination", "relations", "dialogue", "village");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("equip")) {
            List<String> ids = new java.util.ArrayList<>();
            for (com.jubitus.travellers.entity.culture.CultureDef c : CultureRegistry.all()) {
                if (c != null && c.id != null) ids.add(c.id);
            }
            return getListOfStringsMatchingLastWord(args, ids);
        }

        String root = args[0].toLowerCase();
        if (args.length == 2) {
            switch (root) {
                case "trace":
                    return getListOfStringsMatchingLastWord(args, "start", "stop", "list", "channels");
                case "profile":
                    return getListOfStringsMatchingLastWord(args, "start", "stop", "report", "status");
                case "mount":
                    return getListOfStringsMatchingLastWord(args, "give", "ride", "lead", "hitch");
                case "path":
                    return getListOfStringsMatchingLastWord(args, "pos1", "pos2", "mark", "clearmarks",
                            "save", "load", "test", "stop", "cancel", "show", "export");
                case "elytra":
                    return getListOfStringsMatchingLastWord(args, "give", "status");
                case "camp":
                    return getListOfStringsMatchingLastWord(args, "here", "recompute", "join", "leave", "reset");
                case "battle":
                    return getListOfStringsMatchingLastWord(args, "list", "info", "end", "spawnfight", "loot");
                case "map":
                    return getListOfStringsMatchingLastWord(args, "resweep", "stats");
                case "reset":
                    return getListOfStringsMatchingLastWord(args, "workers");
                case "bow":
                    return getListOfStringsMatchingLastWord(args, "status");
                case "sound":
                    return getListOfStringsMatchingLastWord(args, "stress", "rapidfire");
                case "stable":
                    return getListOfStringsMatchingLastWord(args, "list", "dump", "log", "verify");
                case "terrain":
                    return getListOfStringsMatchingLastWord(args, "region", "export");
                case "village":
                    return getListOfStringsMatchingLastWord(args, "show", "hide", "here", "visit", "needs");
                case "dialogue":
                    // "setup" is temporary: opens GuiDialogueSetup directly, ahead of the real entry
                    // point the dialogue plan's Part C builds. See MsgOpenDialogueSetupGui's class doc.
                    return getListOfStringsMatchingLastWord(args, "setup", "memory");
                case "destination": {
                    List<String> opts = new java.util.ArrayList<>();
                    opts.add("report");
                    opts.add("log");
                    opts.addAll(nearbyTravellerNames(sender));
                    return getListOfStringsMatchingLastWord(args, opts);
                }
                default:
                    return java.util.Collections.emptyList();
            }
        }

        String sub = args[1].toLowerCase();
        if (args.length == 3) {
            if (root.equals("trace") && sub.equals("channels")) {
                return getListOfStringsMatchingLastWord(args, traceChannelSuggestions());
            }
            if (root.equals("trace") && sub.equals("stop")) {
                List<String> names = nearbyTravellerNames(sender);
                names.add(0, "all");
                return getListOfStringsMatchingLastWord(args, names);
            }
            if (root.equals("trace") && sub.equals("start")) {
                return getListOfStringsMatchingLastWord(args, nearbyTravellerNames(sender));
            }
            if (root.equals("map") && sub.equals("resweep")) {
                return getListOfStringsMatchingLastWord(args, "all", "cancel");
            }
            if (root.equals("map") && sub.equals("stats")) {
                return getListOfStringsMatchingLastWord(args, "reset");
            }
            if (root.equals("camp") && sub.equals("reset")) {
                return getListOfStringsMatchingLastWord(args, "all");
            }
            if (root.equals("path") && sub.equals("test")) {
                return getListOfStringsMatchingLastWord(args, "--mount", "--waypoints");
            }
            if (root.equals("path") && sub.equals("show")) {
                return getListOfStringsMatchingLastWord(args, "--marks", "--trace");
            }
            if (root.equals("path") && (sub.equals("load") || sub.equals("save")) && sender instanceof EntityPlayerMP) {
                return getListOfStringsMatchingLastWord(args,
                        com.jubitus.travellers.debug.path.PathTestManager.savedRouteNames((EntityPlayerMP) sender));
            }
            if (root.equals("destination") && sub.equals("log")) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "status");
            }
            if (root.equals("dialogue") && sub.equals("memory")) {
                return getListOfStringsMatchingLastWord(args, "dump", "clear");
            }
        }
        if (args.length == 4 && root.equals("dialogue") && sub.equals("memory")
                && (args[2].equalsIgnoreCase("dump") || args[2].equalsIgnoreCase("clear"))) {
            return getListOfStringsMatchingLastWord(args, nearbyTravellerNames(sender));
        }
        if (args.length == 4 && root.equals("terrain") && sub.equals("export")) {
            return getListOfStringsMatchingLastWord(args, "all", "height", "water", "amp", "source", "biome");
        }
        if (args.length == 5 && root.equals("terrain") && sub.equals("export")) {
            return getListOfStringsMatchingLastWord(args, "fill");
        }
        return java.util.Collections.emptyList();
    }

    /** Channel names plus "all", and the current selection with one more appended - so building a
     * comma-separated list by tabbing works instead of only ever offering a single name. */
    private List<String> traceChannelSuggestions() {
        List<String> out = new java.util.ArrayList<>();
        out.add("all");
        for (TraceChannel c : TraceChannel.values()) out.add(c.lower());
        return out;
    }

    private List<String> nearbyTravellerNames(ICommandSender sender) {
        List<String> names = new java.util.ArrayList<>();
        if (!(sender instanceof EntityPlayerMP)) return names;
        EntityPlayerMP player = (EntityPlayerMP) sender;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(64.0D), t -> t != null && t.isEntityAlive())) {
            String name = t.getName();
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2 && args[0].equalsIgnoreCase("reset") && args[1].equalsIgnoreCase("workers")) {
            executeResetWorkers(server, sender);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
            executeReset(server, sender);
            return;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("dialogue") && args[1].equalsIgnoreCase("memory")
                && args[2].equalsIgnoreCase("dump")) {
            executeDialogueMemoryDump(sender, args);
            return;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("dialogue") && args[1].equalsIgnoreCase("memory")
                && args[2].equalsIgnoreCase("clear")) {
            executeDialogueMemoryClear(sender, args);
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("dialogue") && args[1].equalsIgnoreCase("setup")) {
            // Temporary entry point - see MsgOpenDialogueSetupGui's class doc.
            if (sender instanceof EntityPlayerMP) {
                com.jubitus.travellers.server.net.handler.TravellerFollowNet.CHANNEL.sendTo(
                        new com.jubitus.travellers.server.net.message.dialogue.MsgOpenDialogueSetupGui(),
                        (EntityPlayerMP) sender);
            }
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("camp")) {
            executeCampList(server, sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("battle")) {
            executeBattle(sender, args);
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("map") && args[1].equalsIgnoreCase("stats")) {
            executeMapStats(sender, args.length >= 3 && args[2].equalsIgnoreCase("reset"));
            return;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("map") && args[1].equalsIgnoreCase("resweep")
                && args[2].equalsIgnoreCase("cancel")) {
            executeMapResweepCancel(server, sender);
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("map") && args[1].equalsIgnoreCase("resweep")) {
            boolean allDimensions = args.length >= 3 && args[2].equalsIgnoreCase("all");
            executeMapResweep(server, sender, allDimensions);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("elytra")) {
            executeElytraDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("trace")) {
            executeTrace(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("profile")) {
            executeProfile(server, sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("mount")) {
            executeMountDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("stable")) {
            executeStableDebug(server, sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("path")) {
            executePathDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("bow")) {
            executeBowStatus(sender);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("terrain")) {
            executeTerrainDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("village")) {
            executeVillageDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("destination")) {
            executeDestinationDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("relations")) {
            executeRelationsDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("equip")) {
            executeEquipDebug(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("sound")) {
            String sub = args.length >= 2 ? args[1].toLowerCase() : "stress";
            executeSoundDebug(server, sender, sub);
            return;
        }

        // Default: culture dump
        try {
            CultureRegistry.bootstrapAllMillenaireCultures();
        } catch (Exception ex) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Warning: error bootstrapping cultures: " + ex.getMessage()));
        }

        File configDir = Loader.instance().getConfigDir();
        if (configDir == null) {
            throw new CommandException("Config directory is null; cannot write debug file.");
        }

        File outFile = new File(configDir, "traveller_culture_dump.txt");

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {

            dumpEverything(out);

        } catch (IOException e) {
            throw new CommandException("Failed to write debug file: " + e.getMessage());
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Traveller debug exported to: " +
                        TextFormatting.YELLOW + outFile.getAbsolutePath()));
    }

    // --- Village debug: see Millénaire buildings as the mod reads them ---

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
                    + com.jubitus.travellers.world.mill.MillFootprints.count(player.world)
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
            com.jubitus.travellers.world.mill.MillFootprints.Footprint f =
                    com.jubitus.travellers.world.mill.MillFootprints.at(player.world, player.posX, player.posY, player.posZ, 0);
            if (f == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                        + "Not inside any Millénaire building (roof and walls included)."));
                com.jubitus.travellers.world.mill.MillFootprints.Footprint nearest = null;
                double best = Double.MAX_VALUE;
                for (com.jubitus.travellers.world.mill.MillFootprints.Footprint c
                        : com.jubitus.travellers.world.mill.MillFootprints.near(player.world, player.posX, player.posZ, 48)) {
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
    private static void sendFootprintDiag(ICommandSender sender, com.jubitus.travellers.world.mill.MillFootprints.Footprint f,
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
        com.jubitus.travellers.world.mill.MillSettlements.Settlement s =
                com.jubitus.travellers.world.mill.MillSettlements.nearest(world, pos, 256);
        org.millenaire.common.village.Building th = s == null ? null : s.townHall(world);
        if (th == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No Millénaire settlement within 256 blocks."));
            return;
        }
        java.util.List<String> lines = com.jubitus.travellers.world.mill.MillGoods.describeNeeds(world, th);
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + s.displayName() + TextFormatting.RESET
                + " pop " + s.population(world) + ", goal " + th.buildingGoal + ", "
                + lines.size() + " construction item(s) needed"));
        for (String line : lines) sender.sendMessage(new TextComponentString("  " + line));
    }

    /** Where a unit is in its village stay (loaded or offscreen), and where its horse is. */
    private void describeVisit(ICommandSender sender, WorldServer world, String name) {
        String needle = name.toLowerCase(java.util.Locale.ROOT);
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            if (!(e instanceof com.jubitus.travellers.entity.travellers.EntityTraveller) || e.isDead) continue;
            if (!e.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            com.jubitus.travellers.entity.travellers.EntityTraveller t = (com.jubitus.travellers.entity.travellers.EntityTraveller) e;
            String horse;
            if (t.getMountUUID() == null) horse = "none";
            else if (t.getPendingMountNbt() != null) horse = "held as data, post " + fmtPos(t.getPendingMountPos());
            else {
                net.minecraft.entity.Entity h = world.getEntityFromUuid(t.getMountUUID());
                horse = h == null ? "not loaded" : "live at " + fmtPos(h.getPosition()) + " (" + t.getMountMode() + ")";
            }
            sendVisit(sender, "LOADED " + t.getName(), t.getVillageVisitState(), horse);
            return;
        }
        for (com.jubitus.travellers.world.virtualization.VirtualTravellerRecord r
                : com.jubitus.travellers.world.virtualization.VirtualTravellerManager.get(world).getAllRecords()) {
            if (r.name == null || !r.name.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            String horse = r.mountNbt != null ? "data on the record (" + r.mountMode + ")"
                    : r.visit != null && r.visit.horseAtPost ? "live at its post" : r.mounted ? "bonded, not held" : "none";
            sendVisit(sender, "VIRTUAL " + r.name + " at " + (int) r.posX + "," + (int) r.posZ, r.visit, horse);
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No traveller named like '" + name + "'."));
    }

    private static void sendVisit(ICommandSender sender, String who,
                                  @javax.annotation.Nullable com.jubitus.travellers.world.visit.VillageVisitState st, String horse) {
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

    private void executeBattle(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Server world required.");
        WorldServer ws = (WorldServer) w;
        com.jubitus.travellers.world.battle.VirtualBattleManager mgr =
                com.jubitus.travellers.world.battle.VirtualBattleManager.get(ws);
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        if (sub.equals("spawnfight")) {
            int x, z;
            if (args.length >= 4) {
                x = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } else {
                x = (int) sender.getPositionVector().x + 100;
                z = (int) sender.getPositionVector().z;
            }
            spawnTestFight(ws, x, z, sender);
            return;
        }

        if (sub.equals("loot")) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Pending loot drops: " + mgr.getPendingLootCount()));
            return;
        }

        java.util.List<com.jubitus.travellers.world.battle.BattleRecord> list = new java.util.ArrayList<>();
        for (com.jubitus.travellers.world.battle.BattleRecord b : mgr.getBattlesView()) list.add(b);

        if (sub.equals("info") || sub.equals("end")) {
            int idx = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
            if (idx < 0 || idx >= list.size()) throw new CommandException("No battle #" + idx
                    + " (" + list.size() + " active).");
            com.jubitus.travellers.world.battle.BattleRecord b = list.get(idx);
            if (sub.equals("end")) {
                mgr.debugEndBattle(ws, b);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Battle #" + idx + " ended."));
            } else {
                sender.sendMessage(new TextComponentString(describeBattle(ws, b, true)));
            }
            return;
        }

        // list (default)
        if (list.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No active battles."));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "#" + i + " " + describeBattle(ws, list.get(i), false)));
        }
    }

    private static String describeBattle(WorldServer ws, com.jubitus.travellers.world.battle.BattleRecord b,
                                         boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("Battle @ ").append(b.center.getX()).append(",").append(b.center.getZ())
                .append(" sideA=").append(b.countAlive(0))
                .append(" sideB=").append(b.countAlive(1))
                .append(" age=").append((ws.getTotalWorldTime() - b.startTick) / 20).append("s");
        if (b.campAnchorId != null) sb.append(" [camp assault]");
        if (detailed) {
            VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
            for (com.jubitus.travellers.world.battle.BattleParticipant p : b.participants.values()) {
                com.jubitus.travellers.world.virtualization.VirtualTravellerRecord r =
                        vtm.getRecord(p.uuid);
                sb.append("\n  [").append(p.side == 0 ? "A" : "B").append("] ")
                        .append(p.groupKey)
                        .append(p.isGroupLeader ? " LEADER" : "")
                        .append(p.alive ? "" : " DEAD")
                        .append(r != null
                                ? String.format(" hp=%.1f @%d,%d (%s)", r.getHealthResolved(),
                                        (int) r.posX, (int) r.posZ, r.getCombatProfile())
                                : (p.alive ? " [loaded]" : ""));
            }
        }
        return sb.toString();
    }

    /** Spawns two hostile virtual groups ~30 blocks apart so a battle starts on the next detection pass. */
    private static void spawnTestFight(WorldServer ws, int x, int z, ICommandSender sender) {
        VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
        net.minecraft.world.DifficultyInstance diff = new net.minecraft.world.DifficultyInstance(
                ws.getDifficulty(), ws.getWorldTime(), 0, 0.5f);
        int y = ws.getHeight(x, z);

        for (int i = 0; i < 4; i++) {
            spawnTestRecord(ws, vtm, diff, false, x - 15, y, z + i * 2);
            spawnTestRecord(ws, vtm, diff, true, x + 15, y, z + i * 2);
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                + "Spawned 4 virtual travellers vs 4 virtual raiders near " + x + "," + z + "."));
    }

    private static void spawnTestRecord(WorldServer ws, VirtualTravellerManager vtm,
                                        net.minecraft.world.DifficultyInstance diff,
                                        boolean raider, int x, int y, int z) {
        EntityTraveller e = raider
                ? new com.jubitus.travellers.entity.travellers.EntityTravellerRaider(ws)
                : new EntityTraveller(ws);
        e.setPosition(x + 0.5, y, z + 0.5);
        e.onInitialSpawn(diff, null);
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        e.writeToNBT(nbt);
        com.jubitus.travellers.world.virtualization.VirtualTravellerRecord r =
                new com.jubitus.travellers.world.virtualization.VirtualTravellerRecord(
                        e.getUniqueID(), x + 0.5, y, z + 0.5, 0f, ws.provider.getDimension(), nbt);
        r.entityClass = raider ? "RAIDER" : "TRAVELLER";
        r.name = e.getName();
        r.health = e.getMaxHealth();
        vtm.addRecord(r);
        e.setDead();
    }

    // --- Reset: wipe all virtual travellers/raiders and kill loaded ones (camps untouched, see "camp reset") ---

    private void executeReset(MinecraftServer server, ICommandSender sender) {
        int virtualRemoved = 0;
        int loadedKilled = 0;

        for (WorldServer ws : server.worlds) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
            virtualRemoved += mgr.clearByClass("TRAVELLER", "RAIDER");

            for (Entity e : new java.util.ArrayList<>(ws.loadedEntityList)) {
                if (e instanceof EntityTravellerWorker) continue;
                if (e instanceof EntityTraveller && !e.isDead) {
                    // setDead() bypasses the normal death event entirely, so nothing else here would
                    // ever release a bonded mount - left alone it becomes a permanently orphaned horse.
                    TravellerMountSupport.detachAndKillMountForDebugWipe((EntityTraveller) e);
                    e.setDead();
                    loadedKilled++;
                }
            }
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Reset complete: " +
                TextFormatting.YELLOW + virtualRemoved + TextFormatting.GREEN + " virtual records removed, " +
                TextFormatting.YELLOW + loadedKilled + TextFormatting.GREEN + " loaded entities killed." +
                TextFormatting.GRAY + " Workers and campsites untouched (use 'camp reset' for camps)."));
    }

    // --- Reset workers: wipe non-hired virtual/loaded workers, hired workers are preserved ---

    private void executeResetWorkers(MinecraftServer server, ICommandSender sender) {
        int virtualRemoved = 0;
        int loadedKilled = 0;

        for (WorldServer ws : server.worlds) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
            virtualRemoved += mgr.clearWorkersExceptHired();

            for (Entity e : new java.util.ArrayList<>(ws.loadedEntityList)) {
                if (!(e instanceof EntityTravellerWorker) || e.isDead) continue;
                EntityTravellerWorker worker = (EntityTravellerWorker) e;
                if (worker.isHiredFollower()) continue;
                TravellerMountSupport.detachAndKillMountForDebugWipe(worker);
                worker.setDead();
                loadedKilled++;
            }
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Worker reset complete: " +
                TextFormatting.YELLOW + virtualRemoved + TextFormatting.GREEN + " virtual records removed, " +
                TextFormatting.YELLOW + loadedKilled + TextFormatting.GREEN + " loaded workers killed." +
                TextFormatting.GRAY + " Hired workers preserved."));
    }

    // --- Camp listing: show campsites near the player ---

    private void executeCampList(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            boolean deleteAll = args.length >= 3 && args[2].equalsIgnoreCase("all");
            executeCampReset(server, sender, deleteAll);
            return;
        }

        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Must be run by a player."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer ws = player.getServerWorld();
        com.jubitus.travellers.world.camp.CampsiteManager mgr =
                com.jubitus.travellers.world.camp.CampsiteManager.get(ws);
        net.minecraft.util.math.BlockPos origin = player.getPosition();
        int dim = ws.provider.getDimension();

        if (args.length >= 2 && args[1].equalsIgnoreCase("here")) {
            com.jubitus.travellers.world.camp.CampsiteRecord rec = mgr.forceCreateAt(ws, origin);
            if (rec == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED
                        + "Could not create a campsite here (bad ground or too close to another)."));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Campsite created at "
                        + rec.center + " with " + (rec.slotsComputed ? rec.slots.size() : "?") + " slots."));
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("recompute")) {
            int n = mgr.recomputeNear(ws, origin, 256);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Recomputed slots for " + n + " nearby campsite(s)."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("join")) {
            com.jubitus.travellers.world.camp.CampsiteRecord rec = mgr.nearest(dim, origin, 256);
            if (rec == null || !rec.slotsComputed) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "No campsite with slots within 256b."));
                return;
            }
            int count = 1;
            try { if (args.length >= 3) count = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
            String culture = args.length >= 4 ? args[3] : "default";
            String faction = args.length >= 5 ? args[4].toUpperCase(java.util.Locale.ROOT) : "TRAVELLER";
            long now = ws.getTotalWorldTime();
            long depart = now + 20L * 60L * 60L; // ~1h, stays put for testing
            int placed = 0;
            for (int i = 0; i < count; i++) {
                int slot = mgr.claim(rec, java.util.UUID.randomUUID(), faction, culture, now, depart);
                if (slot < 0) break;
                placed++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Claimed " + placed + " slot(s) as "
                    + faction + " (culture=" + culture + ") at " + rec.center
                    + ". Shelters build on the next camp tick if a template is installed."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
            com.jubitus.travellers.world.camp.CampsiteRecord rec = mgr.nearest(dim, origin, 256);
            if (rec == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "No campsite within 256b."));
                return;
            }
            long now = ws.getTotalWorldTime();
            int n = rec.occupants.size();
            for (com.jubitus.travellers.world.camp.CampOccupant o : rec.occupants.values()) o.departTick = now;
            mgr.markDirty();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Forced " + n
                    + " occupant(s) to leave; camp tears down on the next camp tick."));
            return;
        }

        java.util.List<com.jubitus.travellers.world.camp.CampsiteRecord> near = new java.util.ArrayList<>();
        for (com.jubitus.travellers.world.camp.CampsiteRecord r : mgr.getAll()) {
            if (r.dimension == dim && r.center.distanceSq(origin) <= 512 * 512) near.add(r);
        }
        near.sort(java.util.Comparator.comparingDouble(r -> r.center.distanceSq(origin)));

        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Campsites within 512b: "
                + TextFormatting.YELLOW + near.size() + TextFormatting.GRAY + " (total " + mgr.getAll().size() + ")"));
        int shown = 0;
        for (com.jubitus.travellers.world.camp.CampsiteRecord r : near) {
            if (shown++ >= 10) break;
            int dist = (int) Math.sqrt(r.center.distanceSq(origin));
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "  " + r.center.getX() + "," + r.center.getY()
                    + "," + r.center.getZ() + TextFormatting.GRAY + " dist=" + dist + " slots="
                    + (r.slotsComputed ? r.slots.size() : "?") ));
        }
    }

    /**
     * Full physical camp reset across every dimension: restores all campsite terrain to its
     * pre-camp state (paced across ticks by {@link com.jubitus.travellers.world.camp.CampResetCoordinator},
     * no player presence required). Plain "camp reset" keeps campsite records/slots so they don't
     * need rediscovery; "camp reset all" additionally deletes every campsite so the world starts
     * from zero (rediscovered from scratch as chunks load again). Progress is reported to
     * {@code sender} periodically until each dimension's job finishes.
     */
    private void executeCampReset(MinecraftServer server, ICommandSender sender, boolean deleteAll) {
        int alreadyRunning = 0;
        for (WorldServer ws : server.worlds) {
            if (com.jubitus.travellers.world.camp.CampResetCoordinator.INSTANCE.isActive(ws.provider.getDimension())) {
                alreadyRunning++;
                continue;
            }
            com.jubitus.travellers.world.camp.CampResetCoordinator.INSTANCE.start(ws, sender, deleteAll);
        }
        if (alreadyRunning > 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Camp reset already running in " + alreadyRunning + " dimension(s); skipped."));
        }
    }

    /**
     * On-demand worldmap correction for tiles {@code MapLoadedChunkResampler} can never reach
     * automatically (nobody has visited that chunk since it was first captured, e.g. deep in a
     * bulk-pregenerated world). Plain "map resweep" only processes the sender's current dimension;
     * "map resweep all" processes every loaded dimension. Progress is reported to {@code sender}
     * periodically until each dimension's job finishes.
     */
    private void executeMapResweep(MinecraftServer server, ICommandSender sender, boolean allDimensions) {
        java.util.List<WorldServer> targets = new java.util.ArrayList<>();
        if (allDimensions) {
            targets.addAll(java.util.Arrays.asList(server.worlds));
        } else if (sender instanceof EntityPlayerMP) {
            targets.add(((EntityPlayerMP) sender).getServerWorld());
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Must be run by a player, or use 'map resweep all' to target every dimension."));
            return;
        }

        int alreadyRunning = 0;
        for (WorldServer ws : targets) {
            if (com.jubitus.travellers.server.map.pyramid.MapResweepCoordinator.INSTANCE.isActive(ws.provider.getDimension())) {
                alreadyRunning++;
                continue;
            }
            com.jubitus.travellers.server.map.pyramid.MapResweepCoordinator.INSTANCE.start(ws, sender);
        }
        if (alreadyRunning > 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Map resweep already running in " + alreadyRunning + " dimension(s); skipped."));
        }
    }

    // Worldmap fill counters since the last reset, plus a hole census of the 3x3 level-0 tiles around the sender.
    private void executeMapStats(ICommandSender sender, boolean reset) {
        if (reset) {
            MapFillStats.reset();
            ChunkReader.resetCounters();
            com.jubitus.travellers.world.camp.CampsiteManager.resetDiscoveryCounters();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Map fill counters reset."));
            return;
        }
        msg(sender, TextFormatting.AQUA + "Chunk reader (terrain + worldmap + camps, since last reset)");
        msg(sender, TextFormatting.GRAY + String.format(
                "  queued now %d (peak %d)  enqueued %d  read %d (at unload %d)  LOST %d  time %.0f ms",
                ChunkReader.queuedCount(), ChunkReader.queuePeak(), ChunkReader.enqueuedCount(),
                ChunkReader.readCount(), ChunkReader.readAtUnloadCount(), ChunkReader.lostCount(),
                ChunkReader.readNanos() / 1e6));
        msg(sender, TextFormatting.GRAY + "  " + ChunkReader.changeStats());
        msg(sender, TextFormatting.GRAY + "  camps: " + com.jubitus.travellers.world.camp.CampsiteManager.discoveryStats());
        msg(sender, TextFormatting.AQUA + "Worldmap");
        msg(sender, TextFormatting.GRAY + String.format(
                "  chunks painted %d  nothing new %d  paint time %.0f ms",
                MapFillStats.painted(), MapFillStats.unchanged(), MapFillStats.paintNanos() / 1e6));
        msg(sender, TextFormatting.GRAY + String.format(
                "  sweep (overworld): processed %d  changed %d  passes %d  last pass %s for %d chunks  time %.0f ms",
                MapFillStats.sweepProcessed(), MapFillStats.sweepChanged(), MapFillStats.sweepPasses(),
                MapFillStats.sweepLastPassTicks() < 0 ? "-" : String.format("%.1fs", MapFillStats.sweepLastPassTicks() / 20.0),
                MapFillStats.sweepLastPassChunks(), MapFillStats.sweepNanos() / 1e6));
        msg(sender, TextFormatting.GRAY + String.format(
                "  pushes: level0 %d  upper levels %d  delayed by throttle %d  push deflate %.0f ms",
                MapFillStats.pushSentLevel0(), MapFillStats.pushSentUpper(), MapFillStats.pushCoalesced(),
                MapFillStats.pushDeflateNanos() / 1e6));
        long deflates = MapFillStats.storeDeflateCount();
        msg(sender, TextFormatting.GRAY + String.format(
                "  tile store compression (mostly save thread): %d tiles, %.0f ms",
                deflates, MapFillStats.storeDeflateNanos() / 1e6));

        if (!(sender instanceof Entity)) return;
        Entity e = (Entity) sender;
        net.minecraft.world.World world = e.world;
        int tileBlocks = com.jubitus.travellers.server.map.pyramid.MapTileGenerator.TILE_PX;
        int tx = Math.floorDiv((int) Math.floor(e.posX), tileBlocks);
        int tz = Math.floorDiv((int) Math.floor(e.posZ), tileBlocks);
        com.jubitus.travellers.server.map.pyramid.MapPyramidStore level0 =
                com.jubitus.travellers.server.map.pyramid.MapPyramidStore.get(world, 0);
        MapFillStats.HoleCensus total =
                new MapFillStats.HoleCensus();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                byte[] tile = level0.get(com.jubitus.travellers.server.map.pyramid.TileKey.pack(tx + dx, tz + dz));
                total.add(MapFillStats.census(tile));
            }
        }
        msg(sender, TextFormatting.AQUA + "Holes in the 3x3 map tiles around you (768x768 blocks, per chunk)");
        msg(sender, TextFormatting.GRAY + String.format(
                "  full %d  empty %d  PARTIAL %d (edge-line only %d, %d missing pixels)",
                total.fullChunks, total.emptyChunks, total.partialChunks,
                total.edgeOnlyPartialChunks, total.missingPixelsInPartial));
    }

    private static void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    /**
     * Stops every running "map resweep" job across every dimension, wherever it was started from
     * (player or server console) and regardless of the sender's current dimension - there's no
     * legitimate reason to want a bulk correction job left running once you've asked to cancel it.
     */
    private void executeMapResweepCancel(MinecraftServer server, ICommandSender sender) {
        int cancelled = 0;
        for (WorldServer ws : server.worlds) {
            if (com.jubitus.travellers.server.map.pyramid.MapResweepCoordinator.INSTANCE.cancel(ws.provider.getDimension(), sender)) {
                cancelled++;
            }
        }
        if (cancelled == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "No map resweep running."));
        }
    }

    // --- Elytra flight test ---

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

    /**
     * {@code /travellersdebug mount give|ride|lead|hitch} - bonds a fresh horse to the nearest traveller within 32
     * blocks, for testing mounted pathfinding without waiting on a natural spawn roll.
     */
    /**
     * {@code /travellersdebug trace start|stop|list|channels} - per-entity movement tracing.
     * "start" with no name picks whichever traveller the player is looking at, falling back to the
     * nearest one, since pointing at the individual misbehaving is the natural gesture when several
     * are in view.
     */
    private void executeTrace(ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        if (sub.equals("channels")) {
            if (args.length < 3) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Channels: "
                        + TraceChannel.join(TravellerTracer.getDefaultChannels())));
                return;
            }
            java.util.EnumSet<TraceChannel> parsed = TraceChannel.parse(args[2]);
            if (parsed == null) throw new CommandException("Unknown channel in: " + args[2]);
            TravellerTracer.setDefaultChannels(parsed);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Trace channels: "
                    + TraceChannel.join(parsed)));
            return;
        }

        if (sub.equals("list")) {
            if (TravellerTracer.sessions().isEmpty()) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No trace running."));
                return;
            }
            for (TraceSession s : TravellerTracer.sessions()) {
                sender.sendMessage(new TextComponentString(TextFormatting.AQUA + s.entityName
                        + TextFormatting.GRAY + (s.automatic ? " (auto) " : " (manual) ") + s.getFile().getName()));
            }
            return;
        }

        if (sub.equals("stop")) {
            MinecraftServer server = sender.getServer();
            if (args.length >= 3 && args[2].equalsIgnoreCase("all")) {
                List<String> summaries = TravellerTracer.stopAll(server, "stopped by command");
                if (summaries.isEmpty()) throw new CommandException("No trace running.");
                for (String s : summaries) sendSummary(sender, s);
                return;
            }
            EntityTraveller target = resolveTraceTarget(sender, args, 2);
            String summary = TravellerTracer.stop(server, target.getUniqueID(), "stopped by command");
            if (summary == null) throw new CommandException(target.getName() + " is not being traced.");
            sendSummary(sender, summary);
            return;
        }

        if (!sub.equals("start")) {
            throw new CommandException("Usage: /travellersdebug trace start|stop|list|channels");
        }

        EntityTraveller target = resolveTraceTarget(sender, args, 2);
        TraceSession session = TravellerTracer.start(target, TravellerTracer.getDefaultChannels());
        if (session == null) {
            throw new CommandException("Already tracing " + target.getName() + " (or the file could not be created).");
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Tracing " + target.getName()
                + TextFormatting.GRAY + " -> logs/travellers-trace/" + session.getFile().getName()));
    }

    private void executeProfile(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "start":
                if (!MovementProfiler.start(server)) throw new CommandException("A movement profile is already running.");
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Movement profile started."
                        + TextFormatting.GRAY + " Use /travellersdebug profile report|stop."));
                return;
            case "report":
            case "stop": {
                if (!MovementProfiler.isActive()) throw new CommandException("No movement profile is running.");
                java.io.File f = sub.equals("stop") ? MovementProfiler.stop() : MovementProfiler.report();
                if (f == null) throw new CommandException("Could not write the report, see the server log.");
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + (sub.equals("stop") ? "Movement profile stopped. " : "Report written. ")
                        + TextFormatting.GRAY + "logs/travellers-profile/" + f.getName()));
                return;
            }
            case "status": {
                String status = MovementProfiler.status();
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                        + (status == null ? "No movement profile running." : "Movement profile " + status)));
                return;
            }
            default:
                throw new CommandException("Usage: /travellersdebug profile start|stop|report|status");
        }
    }

    private void sendSummary(ICommandSender sender, String summary) {
        for (String line : summary.split("\n")) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + line));
        }
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

    private EntityTraveller resolveTraceTarget(ICommandSender sender, String[] args, int nameIndex)
            throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Must be run by a player.");
        EntityPlayerMP player = (EntityPlayerMP) sender;

        List<EntityTraveller> nearby = player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(64.0D), t -> t != null && t.isEntityAlive());
        if (nearby.isEmpty()) throw new CommandException("No traveller within 64 blocks.");

        if (args.length > nameIndex) {
            String wanted = args[nameIndex];
            for (EntityTraveller t : nearby) {
                if (t.getName().equalsIgnoreCase(wanted)) return t;
            }
            for (EntityTraveller t : nearby) {
                if (t.getName().toLowerCase().startsWith(wanted.toLowerCase())) return t;
            }
            throw new CommandException("No traveller named '" + wanted + "' within 64 blocks.");
        }

        Vec3d eye = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        EntityTraveller bestLooked = null;
        double bestLookedDist = Double.MAX_VALUE;
        EntityTraveller nearest = null;
        double nearestSq = Double.MAX_VALUE;

        for (EntityTraveller t : nearby) {
            double dSq = t.getDistanceSq(player);
            if (dSq < nearestSq) {
                nearestSq = dSq;
                nearest = t;
            }
            // Generous inflation: a traveller several dozen blocks off only needs to be roughly under
            // the crosshair for the intent to be unambiguous.
            AxisAlignedBB box = t.getEntityBoundingBox().grow(0.6D);
            net.minecraft.util.math.RayTraceResult hit = box.calculateIntercept(eye, eye.add(look.scale(64.0D)));
            if (hit != null && Math.sqrt(dSq) < bestLookedDist) {
                bestLookedDist = Math.sqrt(dSq);
                bestLooked = t;
            }
        }
        return bestLooked != null ? bestLooked : nearest;
    }

    private void executeMountDebug(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        if (!sub.equals("give") && !sub.equals("ride") && !sub.equals("lead") && !sub.equals("hitch")) {
            throw new CommandException("Usage: /travellersdebug mount give|ride|lead|hitch");
        }

        EntityTraveller closest = null;
        double bestSq = Double.MAX_VALUE;
        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class,
                player.getEntityBoundingBox().grow(32.0D, 32.0D, 32.0D),
                t -> t != null && t.isEntityAlive())) {
            double dSq = t.getDistanceSq(player);
            if (dSq < bestSq) {
                bestSq = dSq;
                closest = t;
            }
        }

        if (closest == null) {
            throw new CommandException("No traveller within 32 blocks.");
        }

        // Mode switches: the same entry point the AI tasks use, so testing a mode by hand exercises
        // exactly the code path the game will.
        if (!sub.equals("give")) {
            if (closest.getMountUUID() == null) {
                throw new CommandException(closest.getName() + " has no bonded mount.");
            }
            com.jubitus.travellers.mount.MountMode mode =
                    sub.equals("ride") ? com.jubitus.travellers.mount.MountMode.MOUNTED
                            : sub.equals("lead") ? com.jubitus.travellers.mount.MountMode.LED
                            : com.jubitus.travellers.mount.MountMode.HITCHED;
            com.jubitus.travellers.mount.TravellerMountSupport.setMountMode(closest, mode);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + closest.getName() + " mount mode -> " + mode.name()));
            return;
        }

        if (closest.getMountUUID() != null) {
            throw new CommandException(closest.getName() + " already has a bonded mount.");
        }

        if (com.jubitus.travellers.mount.TravellerMountSupport.bondNewMount(closest)) {
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Bonded a horse to " + closest.getName() + "."));
        } else {
            String why = !com.jubitus.travellers.config.TravellersConfig.MOUNTS.enabled
                    ? "mounts disabled in config" : "could not spawn the horse (no room?)";
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Failed: " + why + "."));
        }
    }

    // --- Stable debug: list / dump <n> / log <n> [count] / verify <n> ---

    private void executeStableDebug(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Server world required.");
        java.util.UUID owner = player.getUniqueID();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        java.util.List<com.jubitus.travellers.world.zone.PlayerZoneRecord> stables = new java.util.ArrayList<>();
        for (WorldServer ws : server.worlds) {
            for (com.jubitus.travellers.world.zone.PlayerZoneRecord zone :
                    com.jubitus.travellers.world.zone.PlayerZoneManager.get(ws)
                            .getByOwnerAndType(owner, com.jubitus.travellers.world.zone.ZoneType.STABLE)) {
                if (zone.data instanceof com.jubitus.travellers.world.zone.stable.StableZoneData) stables.add(zone);
            }
        }
        if (stables.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "You own no Stable zones."));
            return;
        }

        WorldServer overworld = server.getWorld(0);
        com.jubitus.travellers.world.zone.stable.StableCoordinator coord =
                com.jubitus.travellers.world.zone.stable.StableCoordinator.get(overworld);

        if ("list".equals(sub)) {
            int hired = com.jubitus.travellers.world.zone.stable.StableCapacity.hiredWorkerCount(overworld, owner);
            int cap = hired * com.jubitus.travellers.config.TravellersConfig.STABLES.horseCapPerWorker;
            int total = com.jubitus.travellers.world.zone.stable.StableCapacity.currentHorseCount(overworld, owner);
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Stable horses: " + total + " / " + cap + "  (" + hired + " hired x "
                    + com.jubitus.travellers.config.TravellersConfig.STABLES.horseCapPerWorker
                    + ")   " + stables.size() + " stable(s).  dump/log/verify <n>"));
            for (int i = 0; i < stables.size(); i++) {
                com.jubitus.travellers.world.zone.PlayerZoneRecord z = stables.get(i);
                String name = (z.name == null || z.name.isEmpty()) ? "Stable" : z.name;
                sender.sendMessage(new TextComponentString(TextFormatting.AQUA + " [" + i + "] " + name
                        + " @ " + z.center.getX() + "," + z.center.getY() + "," + z.center.getZ()));
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "     "
                        + coord.debugSummary((WorldServer) w, z)));
            }
            return;
        }

        int idx;
        try {
            idx = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
        } catch (NumberFormatException e) {
            throw new CommandException("Expected a stable index (see /travellersdebug stable list).");
        }
        if (idx < 0 || idx >= stables.size()) throw new CommandException("No stable [" + idx + "].");
        com.jubitus.travellers.world.zone.PlayerZoneRecord zone = stables.get(idx);
        com.jubitus.travellers.world.zone.stable.StableZoneData data =
                (com.jubitus.travellers.world.zone.stable.StableZoneData) zone.data;

        switch (sub) {
            case "dump": {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "[" + idx + "] "
                        + coord.debugSummary((WorldServer) w, zone)));
                for (com.jubitus.travellers.world.zone.stable.StableHorseEntry e : data.pool) {
                    String state = e.liveEntityUUID != null ? "LIVE   " : (e.horseNbt != null ? "dormant" : "no-nbt ");
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + shortId(e.poolId)
                            + "  " + state + "  " + pad(e.origin.toString(), 18)
                            + " tamed=" + (e.tamed ? "Y" : "n") + " saddled=" + (e.saddled ? "Y" : "n")
                            + " temper=" + e.temper + " age=" + e.growingAge
                            + (e.breedingCooldownUntil > w.getTotalWorldTime() ? " (breed-cd)" : "")));
                }
                break;
            }
            case "log": {
                int n = 20;
                try {
                    if (args.length >= 4) n = Integer.parseInt(args[3]);
                } catch (NumberFormatException ignored) {
                }
                java.util.List<String> lines =
                        com.jubitus.travellers.world.zone.stable.StableWatchLog.tail(zone.id, n);
                if (lines.isEmpty()) {
                    sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                            + "No events logged yet for [" + idx + "] (since last server start)."));
                } else {
                    sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                            + "Last " + lines.size() + " events for [" + idx + "]  (L=loaded, V=offscreen):"));
                    for (String l : lines) {
                        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + l));
                    }
                }
                break;
            }
            case "verify": {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "verify [" + idx + "]:"));
                for (String l : coord.debugVerify((WorldServer) w, zone)) {
                    boolean ok = l.startsWith("OK");
                    sender.sendMessage(new TextComponentString(
                            (ok ? TextFormatting.GREEN : TextFormatting.RED) + "  " + l));
                }
                break;
            }
            default:
                throw new CommandException("Unknown: stable " + sub + " (use list|dump|log|verify).");
        }
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }

    // --- Path comparison tool: mounted route vs. hand-marked ideal route ---

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

    /**
     * {@code /travellersdebug terrain <x> <z>} — dump one terrain profile grid cell.
     * {@code /travellersdebug terrain region <x> <z>} — REAL_SCAN / BIOME_EST / EMPTY breakdown for the region.
     */
    /**
     * {@code /travellersdebug destination}:
     * <ul>
     *   <li>{@code report [trials] [maxOrigins]} - synthetic sweep of the chooser over every nearby
     *       settlement (traveller + raider of its own culture), pick rates vs pool base rates, written
     *       to {@code logs/travellers-destination/report_*.txt}. Instant, no waiting for real legs.</li>
     *   <li>{@code log on|off|status} - toggle {@code DESTINATIONS.logChoices}, the per-decision CSV.</li>
     *   <li>{@code [name]} - explain one nearby traveller's <em>last</em> real choice (only populated
     *       after that entity has actually completed a leg, so usually empty).</li>
     * </ul>
     */
    private void executeDestinationDebug(ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        if (sub.equals("report")) {
            executeDestinationReport(sender, args);
            return;
        }
        if (sub.equals("log")) {
            executeDestinationLog(sender, args);
            return;
        }

        EntityTraveller t = resolveTraceTarget(sender, args, 1);
        String dump = com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser
                .lastDebug(t.getUniqueID());
        if (dump == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + t.getName()
                    + ": no destination choice recorded yet (they choose only at the end of a leg). "
                    + "Use 'destination report' for an immediate world-wide check, or 'destination log on' "
                    + "to record every real choice to CSV."));
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Last destination choice for "
                + TextFormatting.WHITE + t.getName() + TextFormatting.GRAY + ":"));
        for (String line : dump.split("\n")) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + line));
        }
    }

    private void executeDestinationLog(ICommandSender sender, String[] args) {
        String arg = args.length >= 3 ? args[2].toLowerCase() : "status";
        if (arg.equals("on")) {
            com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.logChoices = true;
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Destination choice logging ON -> logs/travellers-destination/choices_*.csv "
                    + "(new file each session). Turn off with 'destination log off'."));
        } else if (arg.equals("off")) {
            com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.logChoices = false;
            com.jubitus.travellers.debug.destination.DestinationChoiceLog.close();
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Destination choice logging OFF."));
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Destination choice logging: "
                    + (com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.logChoices ? "ON" : "OFF")));
        }
    }

    private void executeDestinationReport(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Must run on the server.");
        int trials = args.length >= 3 ? parseInt(args[2], 1, 500) : 150;
        int maxOrigins = args.length >= 4 ? parseInt(args[3], 1, 300) : 80;
        BlockPos near = sender instanceof Entity
                ? new BlockPos(((Entity) sender).posX, ((Entity) sender).posY, ((Entity) sender).posZ)
                : BlockPos.ORIGIN;

        long t0 = System.currentTimeMillis();
        java.util.List<com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.OriginStat> rows =
                com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser
                        .dryRunReport(w, near, maxOrigins, trials);
        long ms = System.currentTimeMillis() - t0;

        if (rows.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "No settlements with a known culture in range - nothing to report."));
            return;
        }

        // Per-kind aggregates (mean of the per-origin deltas).
        for (com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.EntityKind kind :
                new com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.EntityKind[]{
                        com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.EntityKind.TRAVELLER,
                        com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.EntityKind.RAIDER}) {
            int n = 0;
            double dSame = 0, pickDist = 0, poolDist = 0, pickPop = 0, poolPop = 0,
                    pickRel = 0, poolRel = 0, dRoad = 0, eps = 0;
            for (com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.OriginStat s : rows) {
                if (s.kind != kind) continue;
                n++;
                dSame += s.pickSameCultureRate - s.poolSameCultureFrac;
                pickDist += s.meanPickDistFrac;
                poolDist += s.meanPoolDistFrac;
                pickPop += s.meanPickPop;
                poolPop += s.meanPoolPop;
                pickRel += s.meanPickRelation;
                poolRel += s.meanPoolRelation;
                dRoad += s.pickRoadRate - s.poolRoadFrac;
                eps += s.epsilonRate;
            }
            if (n == 0) continue;
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + kind.name()
                    + TextFormatting.GRAY + " (" + n + " origins, " + trials + " trials each):"));
            sender.sendMessage(new TextComponentString(TextFormatting.WHITE + String.format(java.util.Locale.ROOT,
                    "  same-culture pick %+.2f vs base | dist %.3f vs pool %.3f | pop %.1f vs pool %.1f | "
                            + "rel %+.1f vs pool %+.1f | road pick %+.2f | epsilon %.3f",
                    dSame / n, pickDist / n, poolDist / n, pickPop / n, poolPop / n,
                    pickRel / n, poolRel / n, dRoad / n, eps / n)));
        }

        // Full per-origin table to a file for hand-off.
        File dir = new File("logs/travellers-destination");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
                .format(new java.util.Date());
        File out = new File(dir, "report_" + ts + ".tsv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            pw.println("kind\toriginX\toriginZ\tculture\ttrials\tpoolCount\t"
                    + "pickSameCultureRate\tpoolSameCultureFrac\tmeanPickDistFrac\tmeanPoolDistFrac\t"
                    + "meanPickPop\tmeanPoolPop\tmeanPickRelation\tmeanPoolRelation\t"
                    + "pickRoadRate\tpoolRoadFrac\tepsilonRate\tladderNonFull");
            for (com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.OriginStat s : rows) {
                pw.println(String.format(java.util.Locale.ROOT,
                        "%s\t%d\t%d\t%s\t%d\t%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.2f\t%.2f\t%.2f\t%.2f\t%.4f\t%.4f\t%.4f\t%d",
                        s.kind, s.origin.getX(), s.origin.getZ(), s.culture, s.trials, s.poolCount,
                        s.pickSameCultureRate, s.poolSameCultureFrac, s.meanPickDistFrac, s.meanPoolDistFrac,
                        s.meanPickPop, s.meanPoolPop, s.meanPickRelation, s.meanPoolRelation,
                        s.pickRoadRate, s.poolRoadFrac, s.epsilonRate, s.ladderNonFull));
            }
        } catch (IOException e) {
            throw new CommandException("Failed to write report: " + e.getMessage());
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Full table -> "
                + TextFormatting.YELLOW + out.getPath() + TextFormatting.GRAY + "  (" + ms + " ms)"));
    }

    /** {@code /travellersdebug relations <hx> <hz> <dx> <dz>} - raw Millenaire relation + bucket name. */
    private void executeRelationsDebug(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Must run on the server.");
        if (args.length < 5) {
            throw new CommandException("Usage: /travellersdebug relations <homeX> <homeZ> <destX> <destZ>");
        }
        int hx = (int) Math.floor(parseCoord(sender, args[1], true));
        int hz = (int) Math.floor(parseCoord(sender, args[2], false));
        int dx = (int) Math.floor(parseCoord(sender, args[3], true));
        int dz = (int) Math.floor(parseCoord(sender, args[4], false));
        Integer rel = com.jubitus.travellers.world.mill.MillRelations.getRelation(
                w, new net.minecraft.util.math.BlockPos(hx, 0, hz),
                new net.minecraft.util.math.BlockPos(dx, 0, dz), w.getTotalWorldTime());
        String bucket;
        if (rel == null) {
            bucket = "unknown/not-loaded (treated as neutral)";
        } else if (rel <= -90) bucket = "OPEN CONFLICT";
        else if (rel <= -50) bucket = "very bad";
        else if (rel < 0) bucket = "poor";
        else if (rel == 0) bucket = "neutral";
        else if (rel < 50) bucket = "fair";
        else if (rel < 90) bucket = "good";
        else bucket = "excellent";
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Relation " + hx + "," + hz
                + " -> " + dx + "," + dz + TextFormatting.WHITE + ": "
                + (rel == null ? "null" : rel.toString()) + TextFormatting.GRAY + " (" + bucket + ")"));
    }

    private void executeTerrainDebug(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Must run on the server.");
        WorldServer ws = (WorldServer) w;

        com.jubitus.travellers.pathing.terrain.TerrainWorldState state =
                com.jubitus.travellers.pathing.terrain.TerrainWorldState.get(ws);
        if (state == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Planned routes are disabled (ROUTING.enablePlannedRoutes = false)."));
            return;
        }
        com.jubitus.travellers.pathing.terrain.TerrainProfileGrid grid = state.getGrid();

        if (args.length >= 2 && args[1].equalsIgnoreCase("export")) {
            executeTerrainExport(sender, ws, state, grid, args);
            return;
        }

        boolean regionMode = args.length >= 2 && args[1].equalsIgnoreCase("region");
        int argOffset = regionMode ? 2 : 1;
        int x, z;
        if (args.length >= argOffset + 2) {
            x = (int) Math.floor(parseCoord(sender, args[argOffset], true));
            z = (int) Math.floor(parseCoord(sender, args[argOffset + 1], false));
        } else if (sender instanceof Entity) {
            Entity e = (Entity) sender;
            x = (int) Math.floor(e.posX);
            z = (int) Math.floor(e.posZ);
        } else {
            throw new CommandException("Usage: /travellersdebug terrain [region] <x> <z>");
        }

        int cellX = com.jubitus.travellers.pathing.terrain.TerrainProfile.cellCoord(x);
        int cellZ = com.jubitus.travellers.pathing.terrain.TerrainProfile.cellCoord(z);

        // Debug command: block-load the region and fill this cell's estimate so there is something to show.
        grid.loadRegionBlocking(cellX, cellZ);
        grid.ensureEstimated(ws, x, z, x, z);

        if (regionMode) {
            com.jubitus.travellers.pathing.terrain.TerrainProfileGrid.RegionStats st =
                    grid.statsForCell(cellX, cellZ);
            int total = com.jubitus.travellers.pathing.terrain.TerrainProfile.CELLS_PER_REGION;
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Terrain region for cell "
                    + cellX + "," + cellZ + TextFormatting.WHITE
                    + " resident=" + st.resident + " dirty=" + st.dirty));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + String.format(
                    "  REAL_SCAN %d (%.1f%%)  BIOME_EST %d (%.1f%%)  EMPTY %d (%.1f%%)",
                    st.realScan, 100.0 * st.realScan / total,
                    st.biomeEst, 100.0 * st.biomeEst / total,
                    st.empty, 100.0 * st.empty / total)));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                    + "  resident regions in dim " + ws.provider.getDimension() + ": " + grid.residentCount()
                    + "   chunks read by the chunk reader: " + ChunkReader.readCount()
                    + " (at unload: " + ChunkReader.readAtUnloadCount() + ", lost: " + ChunkReader.lostCount() + ")"));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                    + "  chunks measured: " + state.scannedCount()
                    + "   waiting for region file: " + state.heldSampleCount()));
            return;
        }

        byte src = grid.getSource(x, z);
        String srcName = src == com.jubitus.travellers.pathing.terrain.TerrainProfile.SOURCE_REAL_SCAN
                ? "REAL_SCAN" : (src == com.jubitus.travellers.pathing.terrain.TerrainProfile.SOURCE_BIOME_EST
                ? "BIOME_EST" : "EMPTY");
        short biomeIx = grid.getBiomeIx(x, z);
        String biomeName = grid.getBiomeIndex().nameFor(biomeIx);

        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Terrain cell "
                + cellX + "," + cellZ + TextFormatting.WHITE + " (block " + x + "," + z + ")"));
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "  source=" + srcName
                + "  heightMean=" + grid.getHeight(x, z)
                + "  amplitude=" + grid.getAmplitude(x, z)
                + "  water=" + grid.getWater(x, z) + "/255"));
        sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                + "  biomeIx=" + biomeIx + " (" + (biomeName != null ? biomeName : "?") + ")"));
    }

    /** {@code /travellersdebug terrain export [radius] [mode] [fill]} — PNG dump of the scanned grid. */
    private void executeTerrainExport(ICommandSender sender, WorldServer ws,
                                     com.jubitus.travellers.pathing.terrain.TerrainWorldState state,
                                     com.jubitus.travellers.pathing.terrain.TerrainProfileGrid grid,
                                     String[] args) throws CommandException {
        int cx = sender instanceof Entity ? (int) Math.floor(((Entity) sender).posX) : 0;
        int cz = sender instanceof Entity ? (int) Math.floor(((Entity) sender).posZ) : 0;
        int radius = args.length >= 3 ? parseInt(args[2], 128, 8192) : 1024;
        String modeTok = args.length >= 4 ? args[3] : "all";
        boolean fill = args.length >= 5 && args[4].equalsIgnoreCase("fill");

        java.util.List<com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.Mode> modes =
                com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.parseModes(modeTok);
        if (modes.isEmpty()) {
            throw new CommandException("Unknown mode '" + modeTok + "'. Use: height, water, amp, source, biome, all");
        }

        // Make sure anything queued from chunks loaded around the player is scanned in before the snapshot.
        state.tick(ws, ws.getTotalWorldTime());

        try {
            com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.Result r =
                    com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.export(
                            ws.getMinecraftServer(), ws, grid, cx, cz, radius, modes, fill);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Terrain export: "
                    + r.cellsWide + "x" + r.cellsHigh + " cells, " + r.cellPx + " px/cell"));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                    + "  height ramp " + r.minHeight + ".." + r.maxHeight + " (black->white)"
                    + "   REAL_SCAN " + r.realScan + "  BIOME_EST " + r.biomeEst + "  EMPTY " + r.empty
                    + (r.filled ? "  [estimates filled]" : "")));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                    + "  legend: dim cells = biome estimate, bright = real scan, blue = water, red cross = you"));
            for (java.io.File f : r.files) {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "  " + f.getAbsolutePath()));
            }
        } catch (java.io.IOException ex) {
            throw new CommandException("Terrain export failed: " + ex.getMessage());
        }
    }

    private static double parseCoord(ICommandSender sender, String s, boolean isX) throws CommandException {
        double base = 0.0;
        if (sender instanceof Entity) {
            Entity e = (Entity) sender;
            base = isX ? e.posX : e.posZ;
        }
        return parseCoordinate(base, s, false).getResult();
    }

    /** {@code /travellersdebug equip <culture>} — the gear this culture accepts, per bucket, ranked by Millenaire priority. */
    private void executeEquipDebug(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /travellersdebug equip <culture>"));
            return;
        }
        CultureRegistry.bootstrapAllMillenaireCultures();
        String id = args[1].toLowerCase();
        com.jubitus.travellers.entity.culture.CultureDef def = CultureRegistry.get(id);
        if (def == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Unknown culture: " + id));
            return;
        }

        java.util.Map<com.jubitus.travellers.entity.equipment.TravellerMillPriorities.Bucket, java.util.List<ItemStack>> byBucket =
                new java.util.EnumMap<>(com.jubitus.travellers.entity.equipment.TravellerMillPriorities.Bucket.class);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (java.util.List<ItemStack> list : def.getAllCultureItems().values()) {
            for (ItemStack s : list) {
                if (s == null || s.isEmpty()) continue;
                com.jubitus.travellers.entity.equipment.TravellerMillPriorities.Bucket b =
                        com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy.bucketFor(s, null);
                if (b == null) continue;
                if (com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy.isForbidden(s)) continue;
                if (!com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy.cultureAllows(id, false, s, null, false)) continue;
                String key = s.getItem().getRegistryName() + "#" + b;
                if (!seen.add(key)) continue;
                byBucket.computeIfAbsent(b, k -> new java.util.ArrayList<>()).add(s);
            }
        }

        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Equipment kit for culture '" + id + "' ==="));
        for (com.jubitus.travellers.entity.equipment.TravellerMillPriorities.Bucket b :
                com.jubitus.travellers.entity.equipment.TravellerMillPriorities.Bucket.values()) {
            java.util.List<ItemStack> list = byBucket.get(b);
            if (list == null || list.isEmpty()) continue;
            list.sort((x, y) -> Double.compare(
                    com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy.quality(y, b),
                    com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy.quality(x, b)));
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + b.name() + ":"));
            for (ItemStack s : list) {
                Integer raw = com.jubitus.travellers.entity.equipment.TravellerMillPriorities.rawPriority(s, b);
                double q = com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy.quality(s, b);
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + s.getItem().getRegistryName()
                        + TextFormatting.DARK_GRAY + "  q=" + String.format("%.0f", q)
                        + (raw == null ? " (fallback)" : " (mill)")));
            }
        }
    }

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

    private void executeSoundDebug(MinecraftServer server, ICommandSender sender, String sub) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        World world = player.world;

        double radius = 64.0;
        AxisAlignedBB box = player.getEntityBoundingBox().grow(radius, 32, radius);
        List<EntityTraveller> nearby = world.getEntitiesWithinAABB(EntityTraveller.class, box,
                t -> t != null && t.isEntityAlive());

        if (nearby.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "No travellers within " + (int) radius + " blocks."));
            return;
        }

        int triggered = 0;
        if (sub.equals("stress")) {
            // Play ambient (streamed) sounds on all nearby travellers simultaneously.
            // This stresses the sound engine with many concurrent OGG streams.
            for (EntityTraveller t : nearby) {
                SoundEvent snd = pickAmbientOrTalk(t);
                if (snd == null) continue;
                t.playVoiceMoving(snd, 1.0f, 1.0f, 400, true, EntityTraveller.VoiceKind.AMBIENT);
                triggered++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN +
                    "Sound stress: triggered " + triggered + "/" + nearby.size() + " travellers. Check logs for CodecJOrbis errors."));

        } else if (sub.equals("rapidfire")) {
            // Rapid stop+start on each entity: play ambient, immediately replace with talk.
            // This reproduces the race condition where a streamed OGG is stopped and immediately reopened.
            for (EntityTraveller t : nearby) {
                SoundEvent ambient = pickAmbientOrTalk(t);
                SoundEvent talk = pickTalkOrAmbient(t);
                if (ambient == null || talk == null) continue;
                // First play starts the stream
                t.playVoiceMoving(ambient, 1.0f, 1.0f, 200, true, EntityTraveller.VoiceKind.AMBIENT);
                // Immediate replacement triggers the stop+restart race in the sound engine
                t.playVoiceMoving(talk, 1.0f, 1.0f, 200, true, EntityTraveller.VoiceKind.TALK);
                triggered++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN +
                    "Sound rapidfire: triggered " + triggered + "/" + nearby.size() + " stop+restart cycles. Check for CodecJOrbis errors."));

        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED +
                    "Unknown sound subcommand. Use: stress | rapidfire"));
        }
    }

    @Nullable
    private SoundEvent pickAmbientOrTalk(EntityTraveller t) {
        VoiceSet vs = CultureRegistry.TRAVELLER_VOICES.get(t.getCultureId().toLowerCase(java.util.Locale.ROOT));
        if (vs == null) return null;
        if (vs.ambient.length > 0) return vs.ambient[t.getRNG().nextInt(vs.ambient.length)];
        if (vs.talk.length > 0) return vs.talk[t.getRNG().nextInt(vs.talk.length)];
        return null;
    }

    @Nullable
    private SoundEvent pickTalkOrAmbient(EntityTraveller t) {
        VoiceSet vs = CultureRegistry.TRAVELLER_VOICES.get(t.getCultureId().toLowerCase(java.util.Locale.ROOT));
        if (vs == null) return null;
        if (vs.talk.length > 0) return vs.talk[t.getRNG().nextInt(vs.talk.length)];
        if (vs.ambient.length > 0) return vs.ambient[t.getRNG().nextInt(vs.ambient.length)];
        return null;
    }

    private void dumpEverything(PrintWriter out) {
        out.println("Traveller / Raider Culture Debug Dump");
        out.println("====================================");
        out.println();
        out.println("Total cultures: " + CultureRegistry.all().size());
        out.println();

        for (CultureDef c : CultureRegistry.all()) {
            dumpCulture(out, c);
        }

        out.println();
        out.println("======= Summary: Voice Maps =======");
        out.println("Traveller voices map size: " + CultureRegistry.TRAVELLER_VOICES.size());
        out.println("Raider voices map size   : " + CultureRegistry.RAIDER_VOICES.size());
        out.println();

        out.println("TRAVELLER_VOICES keys:");
        for (String key : CultureRegistry.TRAVELLER_VOICES.keySet()) {
            out.println("  - " + key);
        }

        out.println();
        out.println("RAIDER_VOICES keys:");
        for (String key : CultureRegistry.RAIDER_VOICES.keySet()) {
            out.println("  - " + key);
        }
        out.println("======= Biome → Culture Weights =======");
        for (Map.Entry<String, Map<String, Integer>> e : CultureRegistry.getBiomeWeights().entrySet()) {
            out.println("Biome key: " + e.getKey());
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                out.println("  " + c.getKey() + " = " + c.getValue());
            }
        }

        out.println();
        out.println("======= End of Dump =======");
    }

    private void dumpCulture(PrintWriter out, CultureDef c) {
        out.println("------------------------------------");
        out.println("Culture id     : " + c.id);
        out.println("Display name   : " + c.displayName);

        // --- Traveller appearances / textures ---
        try {
            int count = c.travellerAppearanceCount();
            out.println("Traveller looks: " + count);
            for (int i = 0; i < count; i++) {
                TravellerAppearance app = c.getAppearance(i);
                out.println("  [" + i + "] base=" + (app.baseTexture != null ? app.baseTexture.toString() : "<null>"));
                if (app.clothes.isEmpty()) {
                    out.println("       clothes: (none)");
                } else {
                    out.println("       clothes (" + app.clothes.size() + "):");
                    for (int j = 0; j < app.clothes.size(); j++) {
                        out.println("         - " + app.clothes.get(j).toString());
                    }
                }
            }
        } catch (Throwable t) {
            // If you’re still on the old CultureDef with travellerTextureCount/raiderTextureCount,
            // this will catch the missing methods and avoid crashing the command.
            out.println("Traveller looks: <error retrieving> (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }


        VoiceSet tv = CultureRegistry.TRAVELLER_VOICES.get(c.id.toLowerCase(java.util.Locale.ROOT));
        VoiceSet rv = CultureRegistry.RAIDER_VOICES.get(c.id.toLowerCase(java.util.Locale.ROOT));

        out.println("Traveller voices present: " + (tv != null));
        if (tv != null) {
            dumpVoiceArray(out, "  talk", tv.talk);
            dumpVoiceArray(out, "  hurt", tv.hurt);
            dumpVoiceArray(out, "  hello", tv.hello);
            dumpVoiceArray(out, "  ambient", tv.ambient);
        }

        out.println("Raider voices present   : " + (rv != null));
        if (rv != null) {
            dumpVoiceArray(out, "  talk", rv.talk);
            dumpVoiceArray(out, "  hurt", rv.hurt);
            dumpVoiceArray(out, "  hello", rv.hello);
            dumpVoiceArray(out, "  ambient", rv.ambient);
        }

        out.println();
    }

    private void dumpVoiceArray(PrintWriter out, String label, @Nullable SoundEvent[] arr) {
        if (arr == null || arr.length == 0) {
            out.println(label + ": (none)");
            return;
        }

        out.println(label + " (" + arr.length + "):");
        for (int i = 0; i < arr.length; i++) {
            SoundEvent ev = arr[i];
            out.println("    - [" + i + "] " + (ev != null && ev.getRegistryName() != null
                    ? ev.getRegistryName().toString()
                    : "<null>"));
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP only
    }
}