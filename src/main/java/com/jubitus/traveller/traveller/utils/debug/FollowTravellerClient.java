package com.jubitus.traveller.traveller.utils.debug;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

@SideOnly(Side.CLIENT)
public final class FollowTravellerClient {
    // tuning
    private static final float STOP_RANGE = 3.0f;   // keep this close
    private static final float SPRINT_RANGE = 10.0f;
    private static final float TURN_MAX_STEP = 18f; // deg/tick; smooth turn
    private static final int LOSE_TARGET_TICKS = 60; // stop if unseen x ticks
    private static Integer targetId = null;
    private static boolean active = false;
    private static Entity prevView = null;
    private static int unseenTicks = 0;
    private static Integer savedThirdPerson = null;
    private static Boolean savedSmoothCam = null;

    public static void toggleFollow(int travellerId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!active) {
            active = true;
            targetId = travellerId;

            // Save current view + smooth camera setting
            prevView = mc.getRenderViewEntity();
            savedThirdPerson = mc.gameSettings.thirdPersonView;          // 0/1/2
            savedSmoothCam = mc.gameSettings.smoothCamera;              // cinematic toggle

            // Switch camera to traveller (but only *once*)
            mc.setRenderViewEntity(getTargetEntity());

            // Suggest third-person if currently first-person; user can hit F5 as they like
            if (mc.gameSettings.thirdPersonView == 0) {
                mc.gameSettings.thirdPersonView = 1;
            }

            // Turn on cinematic smoothing while following
            mc.gameSettings.smoothCamera = true;

            // clear any ongoing “attack/use” state (prevents that invalid-attack kick)
            mc.playerController.resetBlockRemoving();
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);

            unseenTicks = 0;
        } else {
            disable();
        }
    }

    @Nullable
    private static Entity getTargetEntity() {
        Minecraft mc = Minecraft.getMinecraft();
        if (targetId == null || mc.world == null) return null;
        return mc.world.getEntityByID(targetId);
    }

    public static void disable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!active) return;
        active = false;

        targetId = null;
        if (prevView != null) mc.setRenderViewEntity(prevView);
        prevView = null;

        // Restore player’s previous camera preferences
        if (savedThirdPerson != null) {
            mc.gameSettings.thirdPersonView = savedThirdPerson;
            savedThirdPerson = null;
        }
        if (savedSmoothCam != null) {
            mc.gameSettings.smoothCamera = savedSmoothCam;
            savedSmoothCam = null;
        }

        // release movement/interaction keys
        setKey(mc.gameSettings.keyBindForward, false);
        setKey(mc.gameSettings.keyBindJump, false);
        setKey(mc.gameSettings.keyBindSprint, false);
        setKey(mc.gameSettings.keyBindAttack, false);
        setKey(mc.gameSettings.keyBindUseItem, false);
    }

    private static void setKey(KeyBinding kb, boolean down) {
        KeyBinding.setKeyBindState(kb.getKeyCode(), down);
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Call from a client tick handler: ClientTickEvent
     */
    public static void onClientTick() {
        if (!active) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            disable();
            return;
        }

        Entity target = getTargetEntity();
        if (target == null || !target.isEntityAlive()) {
            if (++unseenTicks > LOSE_TARGET_TICKS) {
                disable();
            }
            return;
        }
        unseenTicks = 0;

        // Keep camera glued to traveller (if user hasn’t changed it)
        if (mc.getRenderViewEntity() != target) {
            mc.setRenderViewEntity(target);
        }

        // Auto-walk the LOCAL player toward traveller
        EntityPlayerSP me = mc.player;
        double dx = target.posX - me.posX;
        double dz = target.posZ - me.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Smoothly face the traveller (so forward runs toward them)
        float desiredYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float cur = me.rotationYaw;
        float delta = MathHelper.wrapDegrees(desiredYaw - cur);
        delta = MathHelper.clamp(delta, -TURN_MAX_STEP, TURN_MAX_STEP);
        me.rotationYaw = cur + delta;
        me.rotationYawHead = me.rotationYaw;

        // Optional: small pitch correction to look slightly down or level
        me.rotationPitch = MathHelper.clamp(me.rotationPitch, -45f, 45f);

        // Press/release movement keys
        boolean go = dist > STOP_RANGE;
        setKey(mc.gameSettings.keyBindForward, go);
        setKey(mc.gameSettings.keyBindSprint, dist > SPRINT_RANGE && go);

        // Hop if a 1-block ledge is ahead (cheap probe)
        setKey(mc.gameSettings.keyBindJump, go && needsJumpAhead(me));

        // Safety: auto-cancel if player provides manual movement input
        if (userSteering()) {
            disable();
        }
    }

    /**
     * Very light “ledge” test ahead of the player.
     */
    private static boolean needsJumpAhead(EntityPlayerSP me) {
        double lookX = -MathHelper.sin(me.rotationYaw * 0.017453292F);
        double lookZ = MathHelper.cos(me.rotationYaw * 0.017453292F);
        double px = me.posX + lookX * 0.8;
        double pz = me.posZ + lookZ * 0.8;
        BlockPos feetAhead = new BlockPos(px, me.posY, pz);
        World w = me.world;
        // jump if the block in front is a step up and head space is clear
        IBlockState floor = w.getBlockState(feetAhead);
        IBlockState head = w.getBlockState(feetAhead.up());
        boolean blocked = !head.getMaterial().isReplaceable();
        boolean stepUp = !floor.getMaterial().isReplaceable();
        return stepUp && !blocked;
    }

    private static boolean userSteering() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.gameSettings.keyBindLeft.isKeyDown() ||
                mc.gameSettings.keyBindRight.isKeyDown() ||
                mc.gameSettings.keyBindBack.isKeyDown() ||
                mc.gameSettings.keyBindForward.isKeyDown(); // user pressing will cancel
    }
}

