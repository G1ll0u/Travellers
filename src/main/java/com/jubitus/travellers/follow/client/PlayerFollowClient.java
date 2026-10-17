package com.jubitus.travellers.follow.client;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.follow.PlayerFollowTrail;
import com.jubitus.travellers.follow.net.MsgPlayerFollowState;
import com.jubitus.travellers.follow.net.MsgStopPlayerFollow;
import com.jubitus.travellers.net.TravellersNetwork;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovementInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

/**
 * Client half of the hands-off follow: records the followed unit's trail and drives the local
 * player's movement input along it. The player's own keys cancel the follow; the body direction is
 * expressed as forward/strafe relative to the current view, so the view is free to turn on its own.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = Tags.MOD_ID)
public final class PlayerFollowClient {

    private static final double START_WALK_DIST = 3.5D;
    private static final double STOP_WALK_DIST = 2.5D;
    private static final double SPRINT_DIST = 9.0D;
    private static final double STEP_UP_HEIGHT = 0.6D;
    private static final double GAP_LOOKAHEAD = 0.9D;

    private static int targetEntityId = MsgPlayerFollowState.NONE;
    private static final PlayerFollowTrail TRAIL = new PlayerFollowTrail();
    private static boolean walking;
    private static final double HOP_DIST = 6.0D;
    private static double lastPlayerX;
    private static double lastPlayerZ;
    private static boolean hasLastPlayerPos;

    private PlayerFollowClient() {
    }

    public static void onServerState(int entityId) {
        targetEntityId = entityId;
        TRAIL.clear();
        walking = false;
        hasLastPlayerPos = false;
    }

    public static boolean isActive() {
        return targetEntityId != MsgPlayerFollowState.NONE;
    }

    public static int targetEntityId() {
        return targetEntityId;
    }

    public static boolean isWalking() {
        return walking;
    }

    /** A point on the route ahead of the player, or null when there is no trail. */
    @Nullable
    public static double[] routeAhead(double px, double pz, double distance) {
        return TRAIL.pointAhead(px, pz, distance);
    }

    @Nullable
    public static Entity currentTarget() {
        return target(Minecraft.getMinecraft());
    }

    /** Ends the follow from the client side and tells the server. */
    public static void cancel() {
        if (!isActive()) return;
        onServerState(MsgPlayerFollowState.NONE);
        TravellersNetwork.CHANNEL.sendToServer(new MsgStopPlayerFollow());
    }

    @Nullable
    private static Entity target(Minecraft mc) {
        if (mc.world == null || !isActive()) return null;
        Entity e = mc.world.getEntityByID(targetEntityId);
        return e != null && !e.isDead ? e : null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            onServerState(MsgPlayerFollowState.NONE);
            return;
        }
        // A hop (boat, rescue) leaves the old crumbs behind the player: start the trail afresh.
        double hx = mc.player.posX - lastPlayerX, hz = mc.player.posZ - lastPlayerZ;
        if (hasLastPlayerPos && hx * hx + hz * hz > HOP_DIST * HOP_DIST) TRAIL.clear();
        lastPlayerX = mc.player.posX;
        lastPlayerZ = mc.player.posZ;
        hasLastPlayerPos = true;

        Entity t = target(mc);
        if (t != null) TRAIL.record(t.posX, t.posY, t.posZ);
    }

    @SubscribeEvent
    public static void onInput(InputUpdateEvent event) {
        if (!isActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP me = mc.player;
        MovementInput in = event.getMovementInput();
        if (me == null || event.getEntityPlayer() != me) return;

        // At this point the *KeyDown/jump/sneak fields still hold the player's real keys.
        if (in.forwardKeyDown || in.backKeyDown || in.leftKeyDown || in.rightKeyDown || in.jump || in.sneak) {
            cancel();
            return;
        }
        if (me.isRiding()) return; // a vehicle would read our input as steering

        Entity t = target(mc);
        if (t == null) {
            walking = false;
            return;
        }
        double toTargetX = t.posX - me.posX, toTargetZ = t.posZ - me.posZ;
        double targetDist = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
        if (walking ? targetDist <= STOP_WALK_DIST : targetDist <= START_WALK_DIST) {
            walking = false;
            TRAIL.clear();
            return;
        }
        walking = true;

        double[] wp = TRAIL.nextWaypoint(me.posX, me.posY, me.posZ);
        double goalX = wp != null ? wp[0] : t.posX;
        double goalY = wp != null ? wp[1] : t.posY;
        double goalZ = wp != null ? wp[2] : t.posZ;
        double dx = goalX - me.posX, dz = goalZ - me.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-3D) return;
        dx /= dist;
        dz /= dist;

        // Direction relative to the current view: yaw 0 faces +z, a positive strafe is to the left.
        float want = (float) (Math.atan2(-dx, dz) * 180.0D / Math.PI);
        double rel = Math.toRadians(MathHelper.wrapDegrees(want - me.rotationYaw));
        in.moveForward = (float) Math.cos(rel);
        in.moveStrafe = (float) -Math.sin(rel);

        if (targetDist > SPRINT_DIST && in.moveForward >= 0.8F) me.setSprinting(true);
        if (shouldJump(me, mc.world, dx, dz, goalY)) in.jump = true;
    }

    // Only ever from the ground: a jump input while falling with an elytra equipped would open it.
    private static boolean shouldJump(EntityPlayerSP me, World world, double dirX, double dirZ, double goalY) {
        if (me.isInWater()) return goalY >= me.posY - 0.2D;
        if (!me.onGround) return false;
        if (goalY > me.posY + STEP_UP_HEIGHT) return true;
        if (me.collidedHorizontally) return true;
        // A gap: nothing to stand on two blocks deep ahead, while the trail stays level.
        if (goalY < me.posY - 1.0D) return false;
        int x = MathHelper.floor(me.posX + dirX * GAP_LOOKAHEAD);
        int z = MathHelper.floor(me.posZ + dirZ * GAP_LOOKAHEAD);
        int y = MathHelper.floor(me.posY - 0.5D);
        return !isStandable(world, new BlockPos(x, y, z)) && !isStandable(world, new BlockPos(x, y - 1, z));
    }

    private static boolean isStandable(World world, BlockPos pos) {
        IBlockState s = world.getBlockState(pos);
        return s.getCollisionBoundingBox(world, pos) != null;
    }
}
