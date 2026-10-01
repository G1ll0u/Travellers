package com.jubitus.travellers.debug.destination;

import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.ChosenDestination;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelDestinationChooser.Row;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Appends every regionalized destination choice to a CSV under {@code logs/travellers-destination/}
 * when {@code DESTINATIONS.logChoices} is on - the analysable artifact for checking, against real
 * play, that travellers/raiders actually weight culture / relation / population / roads / corridor.
 *
 * <p>Rows are one per shortlist candidate ({@code rowType=CAND}), grouped by {@code choiceId}, plus
 * one {@code rowType=POOL} row per choice carrying the candidate-pool base rates so a pick can be
 * compared against "what was on the menu". Server thread only (both callers are).
 */
public final class DestinationChoiceLog {

    private static final String[] COLUMNS = {
            "choiceId", "tick", "rowType", "kind", "raiderInverted", "epsilon", "ladder",
            "originX", "originZ", "originCulture",
            "candX", "candZ", "candCulture", "sameCulture", "relation", "population", "dist",
            "roadConnected", "corridor",
            "wDist", "wCulture", "wRelation", "wPopulation", "wRecent", "wRoad", "score", "picked",
            "poolCount", "poolSameCultureFrac", "poolMeanDist", "poolMeanPop", "poolMeanRelation", "poolRoadFrac",
            "unit", "side", "wHome", "homeDist", "originHomeDist"
    };

    private static final Object LOCK = new Object();
    private static PrintWriter writer;
    private static long choiceId;

    private DestinationChoiceLog() {}

    public static void logChoice(World world, ChosenDestination cd) {
        if (!(world instanceof WorldServer)) return;
        synchronized (LOCK) {
            PrintWriter w = ensureOpen();
            if (w == null) return;
            long id = ++choiceId;

            String[] row = blank();
            row[0] = Long.toString(id);
            row[1] = Long.toString(cd.tick);
            row[3] = String.valueOf(cd.kind);
            row[4] = Boolean.toString(cd.raiderInverted);
            row[5] = Boolean.toString(cd.epsilon);
            row[6] = q(cd.ladder);
            row[7] = Integer.toString(cd.origin.getX());
            row[8] = Integer.toString(cd.origin.getZ());
            row[9] = q(cd.originCulture);
            row[33] = cd.unit == null ? "" : cd.unit.toString();
            row[34] = cd.loaded ? "loaded" : "virtual";
            row[37] = cd.originHomeDist >= 0 ? Integer.toString((int) cd.originHomeDist) : "";

            // POOL row - the base rates to compare the pick against.
            String[] pool = row.clone();
            pool[2] = "POOL";
            pool[27] = Integer.toString(cd.poolCount);
            pool[28] = f(cd.poolSameCultureFrac);
            pool[29] = f(cd.poolMeanDist);
            pool[30] = f(cd.poolMeanPop);
            pool[31] = f(cd.poolMeanRelation);
            pool[32] = f(cd.poolRoadFrac);
            w.println(join(pool));

            if (cd.shortlist.isEmpty()) {
                String[] pick = row.clone();
                pick[2] = "PICK";
                pick[10] = Integer.toString(cd.dest.getX());
                pick[11] = Integer.toString(cd.dest.getZ());
                pick[26] = "true";
                w.println(join(pick));
            } else {
                for (Row r : cd.shortlist) {
                    String[] cand = row.clone();
                    cand[2] = "CAND";
                    cand[10] = Integer.toString(r.centre.getX());
                    cand[11] = Integer.toString(r.centre.getZ());
                    cand[12] = r.cultureKnown ? (r.sameCulture ? "same" : "foreign") : "?";
                    cand[13] = Boolean.toString(r.sameCulture);
                    cand[14] = r.relation == null ? "" : r.relation.toString();
                    cand[15] = Integer.toString(r.population);
                    cand[16] = Integer.toString((int) r.dist);
                    cand[17] = Boolean.toString(r.roadConnected);
                    cand[18] = r.corridor >= 0f ? f(r.corridor) : "";
                    cand[19] = f(r.terms.distance);
                    cand[20] = f(r.terms.culture);
                    cand[21] = f(r.terms.relation);
                    cand[22] = f(r.terms.population);
                    cand[23] = f(r.terms.recent);
                    cand[24] = f(r.terms.road);
                    cand[35] = f(r.terms.home);
                    cand[36] = r.homeDist >= 0 ? Integer.toString((int) r.homeDist) : "";
                    cand[25] = f(r.score);
                    cand[26] = Boolean.toString(r.picked);
                    w.println(join(cand));
                }
            }
            w.flush();
        }
    }

    private static String[] blank() {
        String[] a = new String[COLUMNS.length];
        java.util.Arrays.fill(a, "");
        return a;
    }

    private static String join(String[] a) {
        return String.join(",", a);
    }

    private static PrintWriter ensureOpen() {
        if (writer != null) return writer;
        try {
            File dir = new File("logs/travellers-destination");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
            File f = new File(dir, "choices_" + ts + ".csv");
            writer = new PrintWriter(new FileWriter(f, true));
            writer.println(String.join(",", COLUMNS));
            writer.flush();
            choiceId = 0;
        } catch (IOException e) {
            System.err.println("[Travellers/Destination] cannot open choice log: " + e);
            writer = null;
        }
        return writer;
    }

    public static void close() {
        synchronized (LOCK) {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        }
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String q(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
