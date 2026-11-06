package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.sound.ModSounds;
import com.jubitus.travellers.village.CultureSnacks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * A seated unit (or one standing at a tavern counter) sipping or eating something, purely cosmetic: the item is only shown (synced for render), the real
 * hands are never touched, nothing is used up and nothing is persisted (a unit always comes back from offscreen
 * empty-handed). It ends itself the moment the unit is no longer sitting (or standing) quietly, so no caller can leave it stuck.
 */
public final class TravellerSnack {

    // A sip/bite lasts as long as a player's; the sound and crumbs follow vanilla's rhythm (last 25 ticks, every 4).
    private static final int SIP_TICKS = 32;
    private static final int PAUSE_MIN = 30;
    private static final int PAUSE_MAX = 70;
    private static final int LAST_HOLD = 20;
    // A seated unit first takes in the view, then waits a moment between its drink and its food.
    private static final int FIRST_DELAY_MIN = 100;
    private static final int FIRST_DELAY_MAX = 300;
    private static final int NEXT_DELAY_MIN = 60;
    private static final int NEXT_DELAY_MAX = 160;
    // At a counter the drink comes soon after arriving.
    private static final int COUNTER_DELAY_MIN = 20;
    private static final int COUNTER_DELAY_MAX = 60;

    private static final float RAISE_STEP = 0.2F;

    private final EntityTraveller unit;
    private int sipsLeft;
    // Server: strength of the alcohol being sipped (TravellerDrunk), 0 for none.
    private int alcohol;
    private int phaseTicks;
    // Server: what is left of this seat's snacks (true = a drink), and the wait before the next one.
    private final java.util.ArrayDeque<Boolean> planned = new java.util.ArrayDeque<>();
    private int nextDelay;
    // Server: the snacks are had standing at a counter rather than seated.
    private boolean standing;
    // Server: the heal-eating task (EntityAIAutoEat) owns the shown item and when it ends; sips loop until then.
    private boolean meal;
    // Server: a photo pose (/travellersdebug pose) sips in a loop, or holds still (SNACK_FREEZE), until released.
    private boolean posed;
    // Client only, eased towards the synced state so the arm moves instead of snapping.
    private float hold, prevHold, raise, prevRaise;

    TravellerSnack(EntityTraveller unit) {
        this.unit = unit;
    }

    public boolean isActive() {
        return !getStack().isEmpty();
    }

    /** The item shown in the main hand while snacking, empty otherwise. */
    public ItemStack getStack() {
        return unit.getDataManager().get(EntityTraveller.SNACK);
    }

    /** True while the item is held to the mouth (the arm raised). */
    public boolean isAtMouth() {
        return unit.getDataManager().get(EntityTraveller.SNACK_AT_MOUTH);
    }

    public boolean isDrinking() {
        return getStack().getItemUseAction() == EnumAction.DRINK;
    }

    /**
     * Server, right after the unit sat down: maybe plans a drink, a bite or both for this seat, played out while it
     * stays seated. Whatever is left is dropped the moment it stands up.
     */
    public void planWhileSeated() {
        if (unit.world.isRemote || !unit.sit.isSitting()) return;
        planned.clear();
        standing = false;
        if (unit.getRNG().nextDouble() >= TravellersConfig.REST_SPOTS.snackChance) return;
        boolean drinkFirst = unit.getRNG().nextBoolean();
        planned.add(drinkFirst);
        if (unit.getRNG().nextInt(3) == 0) planned.add(!drinkFirst); // a third of the time, both
        nextDelay = between(FIRST_DELAY_MIN, FIRST_DELAY_MAX);
        TravellerTracer.event(unit, "snack", "planned " + planned + " (true = drink) in " + nextDelay / 20 + "s");
    }

    /**
     * Server, once the unit stands still at a tavern counter: always a drink, sometimes a bite after it, played out
     * while it stands there. Whatever is left is dropped the moment it walks off.
     */
    public void planAtCounter() {
        if (unit.world.isRemote || meal) return;
        planned.clear();
        standing = true;
        planned.add(true);
        if (unit.getRNG().nextInt(3) == 0) planned.add(false);
        nextDelay = between(COUNTER_DELAY_MIN, COUNTER_DELAY_MAX);
        TravellerTracer.event(unit, "snack", "at the counter " + planned + " (true = drink) in " + nextDelay / 20 + "s");
    }

    /**
     * Server, for the heal-eating task: sips or bites {@code snack} with the same rhythm as a break, seated or not,
     * until {@link #releaseMeal}.
     */
    public void holdForMeal(CultureSnacks.Snack snack) {
        if (unit.world.isRemote) return;
        planned.clear();
        meal = true;
        alcohol = snack.alcohol;
        putAwayWeapon();
        unit.getDataManager().set(EntityTraveller.SNACK, snack.stack.copy());
        sipsLeft = Integer.MAX_VALUE;
        beginSip();
        TravellerTracer.event(unit, "snack", "heal with " + snack.stack.getItem().getRegistryName()
                + (alcohol > 0 ? " alcohol " + alcohol : ""));
    }

    public void releaseMeal() {
        if (unit.world.isRemote || !meal) return;
        meal = false;
        sipsLeft = 0;
        unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, false);
        unit.getDataManager().set(EntityTraveller.SNACK, ItemStack.EMPTY);
    }

    /** Photo pose: shows {@code stack} and sips it in a loop, whatever else asks, until {@link #releasePose}. */
    public void pose(ItemStack stack) {
        if (unit.world.isRemote) return;
        planned.clear();
        meal = false;
        posed = true;
        putAwayWeapon();
        unit.getDataManager().set(EntityTraveller.SNACK, stack.copy());
        unit.getDataManager().set(EntityTraveller.SNACK_FREEZE, -1.0F);
        sipsLeft = Integer.MAX_VALUE;
        beginSip();
    }

    /** Photo pose: holds the item still, {@code raise} of the way from the chest (0) to the mouth (1). */
    public void freezeAt(float raise) {
        if (unit.world.isRemote || !posed) return;
        unit.getDataManager().set(EntityTraveller.SNACK_FREEZE, MathHelper.clamp(raise, 0.0F, 1.0F));
    }

    public void releasePose() {
        if (!posed) return;
        posed = false;
        unit.getDataManager().set(EntityTraveller.SNACK_FREEZE, -1.0F);
        stop();
    }

    public void stop() {
        if (meal || posed) return;
        planned.clear();
        standing = false;
        if (unit.world.isRemote || !isActive()) return;
        TravellerTracer.event(unit, "snack", "interrupted");
        sipsLeft = 0;
        unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, false);
        unit.getDataManager().set(EntityTraveller.SNACK, ItemStack.EMPTY);
    }

    /** Server, every tick after the sitting pose's own tick. */
    void tick() {
        if (posed && unit.getDataManager().get(EntityTraveller.SNACK_FREEZE) >= 0.0F) return;
        if (!isActive()) {
            if (!planned.isEmpty()) tickPlanned();
            else standing = false;
            return;
        }
        if (!posed && !meal && !canSnackNow()) {
            stop();
            return;
        }
        if (isAtMouth()) {
            if (phaseTicks <= 25 && phaseTicks % 4 == 0) sipEffects();
            if (--phaseTicks > 0) return;
            unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, false);
            if (!posed) unit.drunk.sip(alcohol);
            phaseTicks = --sipsLeft > 0 ? PAUSE_MIN + unit.getRNG().nextInt(PAUSE_MAX - PAUSE_MIN + 1) : LAST_HOLD;
            return;
        }
        if (--phaseTicks > 0) return;
        if (sipsLeft > 0) {
            beginSip();
            return;
        }
        unit.getDataManager().set(EntityTraveller.SNACK, ItemStack.EMPTY);
        nextDelay = between(NEXT_DELAY_MIN, NEXT_DELAY_MAX);
    }

    private void tickPlanned() {
        if (!canSnackNow()) {
            planned.clear();
            TravellerTracer.event(unit, "snack", "dropped: no longer " + (standing ? "standing" : "sitting") + " quietly");
            standing = false;
            return;
        }
        if (--nextDelay > 0) return;
        boolean drink = planned.poll();
        String culture = unit.getCultureId();
        CultureSnacks.Snack snack = culture == null ? null : CultureSnacks.pick(culture, drink, unit.getRNG());
        if (snack == null && culture != null) snack = CultureSnacks.pick(culture, !drink, unit.getRNG());
        if (snack == null) {
            TravellerTracer.event(unit, "snack", "nothing on the menu of " + culture);
            return;
        }
        sipsLeft = between(1, 3);
        alcohol = snack.alcohol;
        TravellerTracer.event(unit, "snack", snack.stack.getItem().getRegistryName() + " x" + sipsLeft
                + (alcohol > 0 ? " alcohol " + alcohol : ""));
        putAwayWeapon();
        unit.getDataManager().set(EntityTraveller.SNACK, snack.stack.copy());
        beginSip();
    }

    // Nobody eats or drinks with a weapon drawn; in a fight it stays out (the snack only hides it).
    private void putAwayWeapon() {
        if (!unit.combat.getInCombatFlag()) unit.equipment.sheatheNow();
    }

    private int between(int min, int max) {
        return min + unit.getRNG().nextInt(max - min + 1);
    }

    /** Client, every tick: eases the arm towards the synced pose. */
    void clientTick() {
        float frozen = unit.getDataManager().get(EntityTraveller.SNACK_FREEZE);
        if (frozen >= 0.0F && isActive()) {
            prevHold = hold = 1.0F;
            prevRaise = raise = frozen;
            return;
        }
        prevHold = hold;
        prevRaise = raise;
        hold = approach(hold, isActive() ? 1.0F : 0.0F);
        raise = approach(raise, isActive() && isAtMouth() ? 1.0F : 0.0F);
    }

    /** Client: 0 = arm at rest, 1 = item held in front. */
    public float getHold(float partialTicks) {
        return prevHold + (hold - prevHold) * partialTicks;
    }

    /** Client: 0 = item lowered, 1 = item at the mouth. */
    public float getRaise(float partialTicks) {
        return prevRaise + (raise - prevRaise) * partialTicks;
    }

    private static float approach(float v, float target) {
        return v < target ? Math.min(target, v + RAISE_STEP) : Math.max(target, v - RAISE_STEP);
    }

    private void beginSip() {
        phaseTicks = SIP_TICKS;
        unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, true);
    }

    private boolean canSnackNow() {
        boolean still = unit.sit.isSitting()
                || standing && !unit.isRiding() && unit.getNavigator().noPath() && !unit.getMoveHelper().isUpdating();
        return unit.isEntityAlive() && still && unit.hurtTime == 0 && !unit.isInCombat()
                && unit.getAttackTarget() == null && !unit.isHandActive() && !unit.isAutoEating();
    }

    private static float volume() {
        return (float) TravellersConfig.REST_SPOTS.snackVolume;
    }

    // Mirrors EntityLivingBase.updateItemUse: a gulp for a drink, crumbs and a chew for food.
    private void sipEffects() {
        ItemStack stack = getStack();
        if (isDrinking()) {
            if (ModSounds.SNACK_SIP != null) {
                unit.playSound(ModSounds.SNACK_SIP, volume(), unit.world.rand.nextFloat() * 0.1F + 0.95F);
            } else {
                unit.playSound(SoundEvents.ENTITY_GENERIC_DRINK, 0.5F * volume(), unit.world.rand.nextFloat() * 0.1F + 0.9F);
            }
            return;
        }
        unit.playSound(SoundEvents.ENTITY_GENERIC_EAT, (0.5F + 0.5F * unit.getRNG().nextInt(2)) * volume(),
                (unit.getRNG().nextFloat() - unit.getRNG().nextFloat()) * 0.2F + 1.0F);
        if (!(unit.world instanceof WorldServer)) return;
        float yaw = unit.rotationYawHead * 0.017453292F;
        double x = unit.posX - MathHelper.sin(yaw) * 0.3D;
        double z = unit.posZ + MathHelper.cos(yaw) * 0.3D;
        double y = unit.posY + unit.getEyeHeight() - 0.15D
                - (unit.sit.isSitting() ? TravellerSit.SEATED_DROP * unit.getRenderScale() : 0.0D);
        ((WorldServer) unit.world).spawnParticle(EnumParticleTypes.ITEM_CRACK, x, y, z, 5, 0.05D, 0.05D, 0.05D, 0.05D,
                Item.getIdFromItem(stack.getItem()), stack.getMetadata());
    }
}
