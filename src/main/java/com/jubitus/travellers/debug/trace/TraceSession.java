package com.jubitus.travellers.debug.trace;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One traveller being traced to one file, plus the running tallies that become its closing summary.
 *
 * <p>The summary is the part that matters most. Every diagnosis so far has started with the same
 * hand-written pass over a raw log - what share of ticks went nowhere, whether the entity even had a
 * path, whether measured displacement matched what its rider was asking for, and which spots it kept
 * getting stuck at. Computing them here means the answer arrives with the file, and means a wrong
 * turn like "it must be configured too fast" is contradicted by the first line of the report rather
 * than three sessions later.
 */
public final class TraceSession {

    /** How many distinct stall spots to name in the summary. */
    private static final int TOP_SPOTS = 5;
    /** Ticks at one spot before it is worth naming there at all. */
    private static final int SPOT_MIN_TICKS = 20;
    /** Moving samples needed before the steering-ratio verdict means anything at all. */
    private static final int MIN_STEER_VERDICT_SAMPLES = 40;

    public final UUID entityId;
    public final String entityName;
    private final EnumSet<TraceChannel> channels;
    private final File file;
    private BufferedWriter writer;

    /** Server tick at which an automatic capture should stop; -1 for a manual, open-ended trace. */
    private long autoStopTick = -1L;
    public final boolean automatic;

    private int samples;
    private int stalledTicks;
    private int stalledCollidedTicks;
    private int noPathTicks;
    private int mountedTicks;
    private int wedgedTicks;
    private int events;
    private int incidents;
    private double dxzSum;
    private double predictedSum;
    private int predictedSamples;
    private final Map<BlockPos, Integer> stallSpots = new HashMap<>();
    private long startedAtTick;

    TraceSession(EntityTraveller entity, EnumSet<TraceChannel> channels, File folder, boolean automatic,
                 long startedAtTick) throws IOException {
        this.entityId = entity.getUniqueID();
        this.entityName = entity.getName();
        this.channels = channels;
        this.automatic = automatic;
        this.startedAtTick = startedAtTick;

        String stamp = new SimpleDateFormat("HHmmss").format(new Date());
        String safeName = entityName.replaceAll("[^A-Za-z0-9]", "");
        String shortId = entityId.toString().substring(0, 8);
        this.file = new File(folder, safeName + "_" + shortId + "_" + stamp + (automatic ? "_auto" : "") + ".log");

        this.writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));

        writeHeader(entity);
    }

    private void writeHeader(EntityTraveller entity) throws IOException {
        writer.write("# travellers-trace v1\n");
        writer.write("# entity=" + entityName + "  uuid=" + entityId
                + "  class=" + entity.getClass().getSimpleName()
                + "  dim=" + entity.world.provider.getDimension() + "\n");
        writer.write("# started=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                + "  trigger=" + (automatic ? "auto" : "manual")
                + "  channels=" + TraceChannel.join(channels) + "\n");
        writer.write("# S=sample  E=event  !=incident  ~=replayed context from before the trigger\n");
        writer.write("#\n");
    }

    public File getFile() {
        return file;
    }

    void setAutoStopTick(long tick) {
        this.autoStopTick = tick;
    }

    boolean isExpired(long now) {
        return autoStopTick >= 0 && now >= autoStopTick;
    }

    /** Replays the pre-incident buffer so a capture shows how the entity got into trouble, not only
     * that it is in trouble. Marked so it can never be mistaken for live data. */
    void replay(Iterable<TraceSample> before) {
        for (TraceSample s : before) {
            write("~" + s.format(channels).substring(1));
        }
    }

    void sample(TraceSample s) {
        samples++;
        if (s.isStalled()) {
            stalledTicks++;
            if (s.collided || s.horseCollided) stalledCollidedTicks++;
            BlockPos spot = new BlockPos(s.x, s.y, s.z);
            stallSpots.merge(spot, 1, Integer::sum);
        }
        if (!s.hasPath) noPathTicks++;
        if (s.mounted) {
            mountedTicks++;
            if (s.mountWedged) wedgedTicks++;
            // The comparison that separates "steered fast" from "not steered at all". Only sampled
            // while actually moving, since a standing horse trivially matches a zero prediction.
            //
            // The prediction is vanilla's own ground terminal velocity for the steered branch of
            // AbstractHorse.travel(): it feeds the RIDER's moveForward into EntityLivingBase.travel()
            // and scales it by the HORSE's getAIMoveSpeed(). On default slipperiness the per-tick
            // friction is 0.6 * 0.91 = 0.546 and the acceleration coefficient 0.16277 / 0.546^3 is
            // exactly 1, so terminal speed is moveForward * horseAttr / (1 - 0.546). Both factors are
            // read one tick before the horse used them (the vehicle ticks before its passenger, and
            // this samples the rider before its own AI runs), so the two line up rather than lag.
            // Only valid while the rider is the one steering - it is meaningless if the horse is ever
            // put back on its own navigator. Ice/soul sand shift the friction term and are not
            // modelled; a whole route over either would read low here for that reason alone.
            if (s.horseDxz > 0.05D && s.horseAttr > 0.0D) {
                dxzSum += s.horseDxz;
                predictedSum += s.moveForward * s.horseAttr / 0.454D;
                predictedSamples++;
            }
        }
        write(s.format(channels));
    }

    public void event(int tick, String category, String detail) {
        events++;
        write("E " + tick + " " + category + " " + detail);
    }

    /** Writes one line verbatim into the trace. For extra context blocks that are not samples. */
    public void raw(String line) {
        write(line);
    }

    public void incident(int tick, String category, String detail) {
        incidents++;
        write("! " + tick + " " + category + " " + detail);
    }

    private void write(String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            closeQuietly();
        }
    }

    /** Writes the closing report and releases the file. Returns the summary as chat-ready lines. */
    String finish(long endTick) {
        String summary = buildSummary(endTick);
        write("");
        for (String line : summary.split("\n")) {
            write("# " + line);
        }
        closeQuietly();
        return summary;
    }

    private String buildSummary(long endTick) {
        StringBuilder sb = new StringBuilder();
        long ticks = Math.max(1L, endTick - startedAtTick);
        sb.append("=== ").append(entityName).append(" - ").append(samples).append(" samples over ")
                .append(ticks).append(" ticks (").append(formatDuration(ticks)).append(") ===\n");

        int denom = Math.max(1, samples);
        sb.append("movement    moving ").append(pct(samples - stalledTicks, denom))
                .append(" | stalled ").append(pct(stalledTicks, denom))
                .append(" | stalled+collided ").append(pct(stalledCollidedTicks, denom)).append('\n');
        sb.append("navigation  no path ").append(pct(noPathTicks, denom)).append('\n');

        if (mountedTicks > 0) {
            sb.append("mounted     ").append(pct(mountedTicks, denom))
                    .append(" | wedged ").append(pct(wedgedTicks, denom));
            if (predictedSamples >= MIN_STEER_VERDICT_SAMPLES) {
                double ratio = (dxzSum / predictedSamples) / Math.max(1.0E-6D, predictedSum / predictedSamples);
                sb.append(" | dxz/predicted ").append(String.format(Locale.ROOT, "%.2f", ratio))
                        .append(ratio > 1.5D ? "  -> NOT being steered by its rider"
                                : ratio < 0.6D ? "  -> steered but held back (collision?)"
                                : "  -> genuinely rider-steered");
            } else if (predictedSamples > 0) {
                // A horse that spent the whole capture jammed only ever moved in the sidestep nudges,
                // where moveForward is not what produced the motion - the ratio is then pure noise and
                // reads as a spectacular "not being steered", which is exactly the wrong conclusion.
                sb.append(" | dxz/predicted n/a (only ").append(predictedSamples)
                        .append(" moving samples)");
            }
            sb.append('\n');
        }

        sb.append("activity    events ").append(events).append(" | incidents ").append(incidents).append('\n');

        String spots = topSpots();
        if (!spots.isEmpty()) sb.append("black spots ").append(spots).append('\n');
        return sb.toString();
    }

    private String topSpots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TOP_SPOTS; i++) {
            BlockPos best = null;
            int bestCount = SPOT_MIN_TICKS;
            for (Map.Entry<BlockPos, Integer> e : stallSpots.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            if (best == null) break;
            if (sb.length() > 0) sb.append(" | ");
            sb.append('(').append(best.getX()).append(',').append(best.getY()).append(',').append(best.getZ())
                    .append(") x").append(bestCount);
            stallSpots.remove(best);
        }
        return sb.toString();
    }

    private static String pct(int part, int total) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0D * part / total);
    }

    private static String formatDuration(long ticks) {
        long seconds = ticks / 20L;
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private void closeQuietly() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            FMLLog.log(Level.WARN, "[Travellers/Trace] failed to close %s: %s", file.getName(), e);
        }
        writer = null;
    }

    void flush() {
        if (writer == null) return;
        try {
            writer.flush();
        } catch (IOException e) {
            closeQuietly();
        }
    }

    EnumSet<TraceChannel> channels() {
        return channels;
    }
}
