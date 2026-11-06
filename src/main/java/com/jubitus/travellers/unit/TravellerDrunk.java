package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.MathHelper;

import java.util.UUID;

/**
 * Tipsiness after alcohol (TravellerSnack): slower, staggering sideways while walking, missing blows, mumbling.
 * The state is one world time, "sober at", so it wears off by itself offscreen and across restarts; the virtual
 * record carries the same value (offscreen pace, virtual battles). Everything else is derived from it each tick.
 */
public final class TravellerDrunk {

    // Each sip adds this much per point of strength (cider 5 = 90 s, calva 10 = 3 min), capped in total.
    private static final long TICKS_PER_STRENGTH = 18L * 20L;
    private static final long MAX_TICKS = 6L * 60L * 20L;
    // Full sway while more than this is left, fading out below it.
    private static final long FULL_SWAY_TICKS = 2L * 60L * 20L;
    private static final float MIN_SWAY = 0.35F;
    private static final int LURCH_MIN = 30;
    private static final int LURCH_MAX = 90;
    private static final double LURCH_PUSH = 0.22D;
    private static final int MUMBLE_MIN = 10 * 20;
    private static final int MUMBLE_MAX = 30 * 20;
    private static final float MISS_CHANCE = 1.0F / 3.0F;
    /** Extra arrow spread of a tipsy archer (vanilla inaccuracy units). */
    public static final float BOW_SPREAD = 4.0F;

    private static final UUID SLOW_ID = UUID.fromString("5b0f4d7e-3c9a-4f61-9a0e-2d7c8b1e6f42");

    private final EntityTraveller unit;
    private long soberAt;
    // Server: ticks until the next lurch and the next mumble.
    private int lurchIn = LURCH_MAX;
    private int mumbleIn = MUMBLE_MIN;

    TravellerDrunk(EntityTraveller unit) {
        this.unit = unit;
    }

    /** True for a world time before which a unit sobered at {@code soberAt} is still tipsy (also for virtual records). */
    public static boolean isDrunk(long soberAt, long now) {
        return TravellersConfig.REST_SPOTS.drunkenness && now < soberAt;
    }

    /** Offscreen battles: a tipsy pawn misses this blow. */
    public static boolean missesBlow(long soberAt, long now, java.util.Random rng) {
        return isDrunk(soberAt, now) && rng.nextFloat() < MISS_CHANCE;
    }

    public boolean isDrunk() {
        return isDrunk(soberAt, unit.world.getTotalWorldTime());
    }

    public long getSoberAt() {
        return soberAt;
    }

    /** Materialize: the record's value is authoritative. */
    public void setSoberAt(long soberAt) {
        this.soberAt = Math.max(0L, soberAt);
    }

    /** Server, one sip of a drink of this strength (CultureSnacks.Snack.alcohol). */
    public void sip(int strength) {
        if (unit.world.isRemote || strength <= 0 || !TravellersConfig.REST_SPOTS.drunkenness) return;
        long now = unit.world.getTotalWorldTime();
        soberAt = Math.min(Math.max(now, soberAt) + strength * TICKS_PER_STRENGTH, now + MAX_TICKS);
        TravellerTracer.event(unit, "drunk", "sip strength " + strength + ", sober in " + (soberAt - now) / 20 + "s");
    }

    /** Server, a melee blow about to land: true when it goes wide instead. */
    public boolean missesBlow() {
        if (!missesBlow(soberAt, unit.world.getTotalWorldTime(), unit.getRNG())) return false;
        unit.world.playSound(null, unit.posX, unit.posY, unit.posZ, SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE,
                SoundCategory.NEUTRAL, 0.7F, 0.9F + unit.getRNG().nextFloat() * 0.2F);
        return true;
    }

    /** Server, every tick. */
    void tick() {
        long now = unit.world.getTotalWorldTime();
        boolean drunk = isDrunk(soberAt, now);
        float sway = drunk ? Math.max(MIN_SWAY, Math.min(1.0F, (soberAt - now) / (float) FULL_SWAY_TICKS)) : 0.0F;
        float shown = unit.getDataManager().get(EntityTraveller.DRUNK);
        if (sway == 0.0F ? shown != 0.0F : Math.abs(shown - sway) > 0.02F) {
            unit.getDataManager().set(EntityTraveller.DRUNK, sway);
            if (sway == 0.0F) TravellerTracer.event(unit, "drunk", "sober");
        }
        applySlowdown(drunk);
        if (!drunk) return;
        if (--lurchIn <= 0) {
            lurchIn = LURCH_MIN + unit.getRNG().nextInt(LURCH_MAX - LURCH_MIN + 1);
            lurch(sway);
        }
        if (--mumbleIn <= 0) {
            mumbleIn = MUMBLE_MIN + unit.getRNG().nextInt(MUMBLE_MAX - MUMBLE_MIN + 1);
            unit.voice.tryPlayDrunk();
        }
    }

    // A sideways stagger while walking: a real push, so a misstep near an edge can end in a fall.
    private void lurch(float sway) {
        if (!unit.onGround || unit.isRiding() || unit.isInWater() || unit.isInLava() || unit.sit.isSitting()
                || unit.sleep.isSleeping() || unit.isElytraFlying()) return;
        double speedSq = unit.motionX * unit.motionX + unit.motionZ * unit.motionZ;
        if (speedSq < 1.0E-4D && unit.getNavigator().noPath()) return;
        float yaw = unit.rotationYaw * 0.017453292F;
        double side = (unit.getRNG().nextBoolean() ? 1.0D : -1.0D) * LURCH_PUSH * (0.6D + 0.4D * sway);
        // Perpendicular to the heading.
        unit.motionX += MathHelper.cos(yaw) * side;
        unit.motionZ += MathHelper.sin(yaw) * side;
        unit.velocityChanged = true;
    }

    private void applySlowdown(boolean drunk) {
        IAttributeInstance speed = unit.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (speed == null) return;
        boolean has = speed.getModifier(SLOW_ID) != null;
        if (drunk && !has) {
            speed.applyModifier(new AttributeModifier(SLOW_ID, "travellers.drunk", UnitSpeed.DRUNK_FACTOR - 1.0D, 2)
                    .setSaved(false));
        } else if (!drunk && has) {
            speed.removeModifier(SLOW_ID);
        }
    }

    /** Client: 0 = sober, up to 1 = full sway. */
    public float getSway() {
        return unit.getDataManager().get(EntityTraveller.DRUNK);
    }

    void write(NBTTagCompound tag) {
        if (soberAt > 0L) tag.setLong("SoberAt", soberAt);
    }

    void read(NBTTagCompound tag) {
        soberAt = tag.getLong("SoberAt");
    }
}
