package com.jubitus.travellers.follow.client;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.follow.FollowCameraSpring;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Turns the player's view every rendered frame while following: ahead along the route when walking,
 * at the unit's head when stopped. A manual mouse turn pauses it briefly, then it glides back.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = Tags.MOD_ID)
public final class FollowCameraController {

    private static final double LOOK_AHEAD_BLOCKS = 4.0D;
    private static final float WALK_PITCH_BIAS = 6f;
    private static final float MIN_PITCH = -25f;
    private static final float MAX_PITCH = 30f;
    private static final float PLAYER_EYE_HEIGHT = 1.62f;
    private static final long MANUAL_PAUSE_MS = 1200L;
    private static final float MANUAL_TURN_EPSILON_DEG = 0.05f;
    private static final float MAX_FRAME_DT = 0.1f;

    private static final FollowCameraSpring YAW = new FollowCameraSpring(0.30f, 240f);
    private static final FollowCameraSpring PITCH = new FollowCameraSpring(0.25f, 120f);

    private static boolean hasLast;
    private static float lastYaw;
    private static float lastPitch;
    private static long lastFrameNanos;
    private static long manualUntilMillis;

    private FollowCameraController() {
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP me = mc.player;
        if (!PlayerFollowClient.isActive() || me == null || mc.world == null || mc.isGamePaused()) {
            hasLast = false;
            return;
        }
        long nowNanos = System.nanoTime();
        long nowMillis = nowNanos / 1_000_000L;
        float dt = hasLast ? Math.min(MAX_FRAME_DT, (nowNanos - lastFrameNanos) / 1.0E9f) : 0f;
        lastFrameNanos = nowNanos;

        boolean turnedByPlayer = hasLast
                && (Math.abs(MathHelper.wrapDegrees(me.rotationYaw - lastYaw)) > MANUAL_TURN_EPSILON_DEG
                || Math.abs(me.rotationPitch - lastPitch) > MANUAL_TURN_EPSILON_DEG);
        if (turnedByPlayer) manualUntilMillis = nowMillis + MANUAL_PAUSE_MS;

        float yaw = me.rotationYaw;
        float pitch = me.rotationPitch;
        if (nowMillis >= manualUntilMillis) {
            float[] want = desiredView(me);
            if (want != null) {
                yaw = YAW.update(yaw, want[0], dt);
                pitch = PITCH.update(pitch, want[1], dt);
            }
        } else {
            YAW.reset();
            PITCH.reset();
        }

        me.rotationYaw = yaw;
        me.prevRotationYaw = yaw;
        me.rotationPitch = pitch;
        me.prevRotationPitch = pitch;
        lastYaw = yaw;
        lastPitch = pitch;
        hasLast = true;
    }

    /** {yaw, pitch} to look at, or null to keep the current view. */
    private static float[] desiredView(EntityPlayerSP me) {
        double lookX, lookY, lookZ;
        float bias = 0f;
        if (PlayerFollowClient.isWalking()) {
            double[] ahead = PlayerFollowClient.routeAhead(me.posX, me.posZ, LOOK_AHEAD_BLOCKS);
            if (ahead == null) return null;
            lookX = ahead[0];
            lookY = ahead[1] + PLAYER_EYE_HEIGHT;
            lookZ = ahead[2];
            bias = WALK_PITCH_BIAS;
        } else {
            Entity t = PlayerFollowClient.currentTarget();
            if (t == null) return null;
            lookX = t.posX;
            lookY = t.posY + t.getEyeHeight();
            lookZ = t.posZ;
        }
        double dx = lookX - me.posX, dz = lookZ - me.posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.5D) return null;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0D / Math.PI);
        float pitch = (float) -Math.toDegrees(Math.atan2(lookY - (me.posY + me.getEyeHeight()), horiz)) + bias;
        return new float[]{yaw, MathHelper.clamp(pitch, MIN_PITCH, MAX_PITCH)};
    }
}
