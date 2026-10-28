package com.jubitus.travellers.sound.client;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import org.lwjgl.openal.AL10;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active OpenAL sources and periodically re-evaluates SoundPhysics environment
 * as entities move, so reverb/occlusion stays consistent with the entity's actual position.
 *
 * SoundPhysics computes environment only once in SourceLWJGLOpenAL.play(). For long
 * entity-following sounds this snapshot becomes stale as the entity moves. This helper
 * re-calls SoundPhysics.onPlaySound whenever the source or the listener moves significantly
 * (a listener leaving a cathedral next to a standing choir must muffle it).
 *
 * Songs and choirs get a gentler treatment (they must carry further and through walls): a lower
 * distance rolloff on their source, and their evaluations run on the game thread with SoundPhysics'
 * absorption temporarily scaled down.
 *
 * All methods are called on the SoundSystemStarterThread, except runPendingSongEvaluations.
 */
@SideOnly(Side.CLIENT)
public final class SPMovingSoundHelper {

    private static final boolean ENABLED;
    private static Field fLastCategory;
    private static Field fLastName;
    private static Field fLastAtt;
    private static Method mOnPlaySound;
    private static Field fRolloff;
    private static Field fBlockAbsorption;
    private static Field fAirAbsorption;

    static {
        boolean ok = false;
        try {
            Class<?> sp = Class.forName("com.sonicether.soundphysics.SoundPhysics");
            fLastCategory = sp.getDeclaredField("lastSoundCategory");
            fLastCategory.setAccessible(true);
            fLastName = sp.getDeclaredField("lastSoundName");
            fLastName.setAccessible(true);
            fLastAtt = sp.getDeclaredField("lastSoundAtt");
            fLastAtt.setAccessible(true);
            mOnPlaySound = sp.getMethod("onPlaySound",
                    float.class, float.class, float.class, int.class,
                    SoundCategory.class, String.class, ISound.AttenuationType.class);
            fRolloff = sp.getField("globalRolloffFactor");
            ok = true;
            try {
                Class<?> cfg = Class.forName("com.sonicether.soundphysics.Config");
                fBlockAbsorption = cfg.getField("globalBlockAbsorption");
                fAirAbsorption = cfg.getField("airAbsorption");
            } catch (Exception e) {
                fBlockAbsorption = null; // songs then still carry further, but walls muffle them as usual
                fAirAbsorption = null;
            }
        } catch (Exception ignored) {}
        ENABLED = ok;
    }

    private static final ConcurrentHashMap<Integer, SoundContext> CONTEXTS = new ConcurrentHashMap<>();
    // Latest song evaluation wanted per source, run by the game thread (SoundPhysics evaluates at once there).
    private static final ConcurrentHashMap<Integer, PendingSong> PENDING_SONGS = new ConcurrentHashMap<>();
    // SoundPhysics' own first evaluation lands at the end of the game tick; ours must come after it.
    private static final long SONG_FIRST_EVAL_DELAY_MS = 200L;

    // Source moved >= 2 blocks: re-evaluate at most once every 3 seconds.
    private static final long INTERVAL_MS = 3000L;
    private static final float DIST_SQ_THRESHOLD = 4.0f;
    // Listener moved >= 1 block: re-evaluate ~4 times a second, but only for sounds that have
    // played a while (short lines end before it matters) and within a global per-tick budget.
    private static final long LISTENER_INTERVAL_MS = 250L;
    private static final long LISTENER_MIN_AGE_MS = 1000L;
    private static final double LISTENER_DIST_SQ_THRESHOLD = 1.0;
    private static final int LISTENER_EVALS_PER_TICK = 2;
    private static long budgetWindowMs = 0;
    private static int budgetUsed = 0;

    private SPMovingSoundHelper() {}

    /**
     * Called right after SourceLWJGLOpenAL.play() — at that point SP's static
     * lastSoundCategory/lastSoundName/lastSoundAtt reflect this sound's context.
     */
    public static void captureContext(int alSourceId) {
        if (!ENABLED) return;
        try {
            SoundCategory cat = (SoundCategory) fLastCategory.get(null);
            // SP forces RECORDS for MovingSound — those bypass SP processing, skip them.
            ISound.AttenuationType att = (ISound.AttenuationType) fLastAtt.get(null);
            String name = (String) fLastName.get(null);
            if (cat == SoundCategory.RECORDS || cat == SoundCategory.MUSIC
                    || att == ISound.AttenuationType.NONE || name == null) {
                CONTEXTS.remove(alSourceId); // source ids are reused: drop a previous sound's context
                return;
            }
            SoundContext ctx = new SoundContext(cat, name, att);
            CONTEXTS.put(alSourceId, ctx);
            if (ctx.song) carryFurther(alSourceId);
        } catch (Exception ignored) {}
    }

    /**
     * Called from SourceLWJGLOpenAL.setPosition — re-evaluates SP environment
     * if the source has moved enough and enough time has passed.
     */
    public static void onSourceMoved(int alSourceId, float x, float y, float z) {
        SoundContext ctx = CONTEXTS.get(alSourceId);
        if (ctx == null) return;

        long now = System.currentTimeMillis();
        EntityPlayer player = Minecraft.getMinecraft().player;

        if (ctx.song && ctx.firstEvalDueMs > 0 && now >= ctx.firstEvalDueMs) {
            ctx.firstEvalDueMs = 0;
            PENDING_SONGS.put(alSourceId, new PendingSong(ctx, x, y, z));
        }

        boolean sourceMoved = now - ctx.lastEvalMs >= INTERVAL_MS && !(sq(x - ctx.lastX, z - ctx.lastZ) < DIST_SQ_THRESHOLD);
        boolean listenerMoved = player != null
                && now - ctx.lastEvalMs >= LISTENER_INTERVAL_MS
                && now - ctx.startMs >= LISTENER_MIN_AGE_MS
                && !(sq(player.posX - ctx.lastListenerX, player.posZ - ctx.lastListenerZ) < LISTENER_DIST_SQ_THRESHOLD)
                && takeListenerBudget(now);
        if (!sourceMoved && !listenerMoved) return;

        ctx.lastEvalMs = now;
        ctx.lastX = x;
        ctx.lastZ = z;
        if (player != null) {
            ctx.lastListenerX = player.posX;
            ctx.lastListenerZ = player.posZ;
        }

        if (ctx.song) {
            PENDING_SONGS.put(alSourceId, new PendingSong(ctx, x, y, z));
            return;
        }
        try {
            mOnPlaySound.invoke(null, x, y, z, alSourceId, ctx.category, ctx.name, ctx.att);
        } catch (Exception ignored) {}
    }

    /** Game thread: runs the song evaluations queued by the sound thread. */
    public static void runPendingSongEvaluations() {
        if (!ENABLED || PENDING_SONGS.isEmpty()) return;
        float scale = TravellersConfig.SOUND.songWallAbsorption;
        for (Iterator<Map.Entry<Integer, PendingSong>> it = PENDING_SONGS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, PendingSong> e = it.next();
            it.remove();
            PendingSong p = e.getValue();
            float block = 0, air = 0;
            try {
                if (fBlockAbsorption != null) {
                    block = fBlockAbsorption.getFloat(null);
                    air = fAirAbsorption.getFloat(null);
                    fBlockAbsorption.setFloat(null, block * scale);
                    fAirAbsorption.setFloat(null, air * scale);
                }
                mOnPlaySound.invoke(null, p.x, p.y, p.z, e.getKey(), p.ctx.category, p.ctx.name, p.ctx.att);
            } catch (Exception ignored) {
            } finally {
                try {
                    if (fBlockAbsorption != null) {
                        fBlockAbsorption.setFloat(null, block);
                        fAirAbsorption.setFloat(null, air);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    // A song fades with distance as a normal voice does at voiceMaxDistance, but only at songMaxDistance.
    private static void carryFurther(int alSourceId) {
        try {
            double voiceMax = TravellersConfig.SOUND.voiceMaxDistance;
            double songMax = TravellersConfig.SOUND.songMaxDistance;
            if (songMax <= voiceMax) return;
            float rolloff = fRolloff.getFloat(null) * (float) ((voiceMax - 1.0) / (songMax - 1.0));
            AL10.alSourcef(alSourceId, AL10.AL_ROLLOFF_FACTOR, rolloff);
        } catch (Exception ignored) {}
    }

    private static final class PendingSong {
        final SoundContext ctx;
        final float x, y, z;

        PendingSong(SoundContext ctx, float x, float y, float z) {
            this.ctx = ctx;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static double sq(double dx, double dz) {
        return dx * dx + dz * dz;
    }

    private static boolean takeListenerBudget(long now) {
        if (now - budgetWindowMs >= 50) {
            budgetWindowMs = now;
            budgetUsed = 0;
        }
        return budgetUsed++ < LISTENER_EVALS_PER_TICK;
    }

    private static final class SoundContext {
        final SoundCategory category;
        final String name;
        final ISound.AttenuationType att;
        final long startMs = System.currentTimeMillis();
        final boolean song;
        long firstEvalDueMs;
        long lastEvalMs = 0;
        float lastX = Float.NaN;
        float lastZ = Float.NaN;
        double lastListenerX = Double.NaN;
        double lastListenerZ = Double.NaN;

        SoundContext(SoundCategory cat, String name, ISound.AttenuationType att) {
            this.category = cat;
            this.name = name;
            this.att = att;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            this.song = lower.contains("choir") || lower.contains("ambient");
            this.firstEvalDueMs = song ? startMs + SONG_FIRST_EVAL_DELAY_MS : 0;
        }
    }
}
