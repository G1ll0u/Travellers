package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug destination …`. */
public class DestinationDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "destination";
    }

    @Override
    public String description() {
        return "Destination choice reports and log";
    }

    @Override
    public String usage() {
        return "report [trials] [origins]|log [on|off|status]|<traveller>";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeDestinationDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> opts = new ArrayList<>();
            opts.add("report");
            opts.add("log");
            opts.addAll(nearbyTravellerNames(sender));
            return getListOfStringsMatchingLastWord(args, opts);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("log")) return getListOfStringsMatchingLastWord(args, "on", "off", "status");
        return Collections.emptyList();
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
     *       to {@code logs/travellers/destination/report_*.txt}. Instant, no waiting for real legs.</li>
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
        String dump = com.jubitus.travellers.travel.destination.TravelDestinationChooser
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
                    + "Destination choice logging ON -> logs/travellers/destination/choices_*.csv "
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
        java.util.List<com.jubitus.travellers.travel.destination.TravelDestinationChooser.OriginStat> rows =
                com.jubitus.travellers.travel.destination.TravelDestinationChooser
                        .dryRunReport(w, near, maxOrigins, trials);
        long ms = System.currentTimeMillis() - t0;

        if (rows.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "No settlements with a known culture in range - nothing to report."));
            return;
        }

        // Per-kind aggregates (mean of the per-origin deltas).
        for (com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind kind :
                new com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind[]{
                        com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind.TRAVELLER,
                        com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind.RAIDER}) {
            int n = 0;
            double dSame = 0, pickDist = 0, poolDist = 0, pickPop = 0, poolPop = 0,
                    pickRel = 0, poolRel = 0, dRoad = 0, eps = 0;
            for (com.jubitus.travellers.travel.destination.TravelDestinationChooser.OriginStat s : rows) {
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
        File dir = com.jubitus.travellers.debug.DebugFiles.dir(com.jubitus.travellers.debug.DebugFiles.DESTINATION);
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
            for (com.jubitus.travellers.travel.destination.TravelDestinationChooser.OriginStat s : rows) {
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
}
