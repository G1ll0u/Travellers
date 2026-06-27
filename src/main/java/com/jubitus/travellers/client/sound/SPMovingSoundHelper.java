package com.jubitus.travellers.client.sound;

import net.minecraft.client.audio.ISound;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active OpenAL sources and periodically re-evaluates SoundPhysics environment
 * as entities move, so reverb/occlusion stays consistent with the entity's actual position.
 *
 * SoundPhysics computes environment only once in SourceLWJGLOpenAL.play(). For long
 * entity-following sounds this snapshot becomes stale as the entity moves. This helper
 * re-calls SoundPhysics.onPlaySound whenever the source position changes significantly.
 *
 * All methods are called on the SoundSystemStarterThread.
 */
@SideOnly(Side.CLIENT)
public final class SPMovingSoundHelper {

    private static final boolean ENABLED;
    private static Field fLastCategory;
    private static Field fLastName;
    private static Field fLastAtt;
    private static Method mOnPlaySound;

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
            ok = true;
        } catch (Exception ignored) {}
        ENABLED = ok;
    }

    private static final ConcurrentHashMap<Integer, SoundContext> CONTEXTS = new ConcurrentHashMap<>();

    // Re-evaluate at most once every 3 seconds AND only if source moved >= 2 blocks.
    private static final long INTERVAL_MS = 3000L;
    private static final float DIST_SQ_THRESHOLD = 4.0f;

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
            if (cat == SoundCategory.RECORDS || cat == SoundCategory.MUSIC) return;
            ISound.AttenuationType att = (ISound.AttenuationType) fLastAtt.get(null);
            if (att == ISound.AttenuationType.NONE) return;
            String name = (String) fLastName.get(null);
            if (name == null) return;
            CONTEXTS.put(alSourceId, new SoundContext(cat, name, att));
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
        if (now - ctx.lastEvalMs < INTERVAL_MS) return;

        float dx = x - ctx.lastX;
        float dz = z - ctx.lastZ;
        if (dx * dx + dz * dz < DIST_SQ_THRESHOLD) return;

        ctx.lastEvalMs = now;
        ctx.lastX = x;
        ctx.lastZ = z;

        try {
            mOnPlaySound.invoke(null, x, y, z, alSourceId, ctx.category, ctx.name, ctx.att);
        } catch (Exception ignored) {}
    }

    private static final class SoundContext {
        final SoundCategory category;
        final String name;
        final ISound.AttenuationType att;
        long lastEvalMs = 0;
        float lastX = Float.NaN;
        float lastZ = Float.NaN;

        SoundContext(SoundCategory cat, String name, ISound.AttenuationType att) {
            this.category = cat;
            this.name = name;
            this.att = att;
        }
    }
}
