package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.village.CultureSnacks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * A seated unit sipping or eating something, purely cosmetic: the item is only shown (synced for render), the real
 * hands are never touched, nothing is used up and nothing is persisted (a unit always comes back from offscreen
 * empty-handed). It ends itself the moment the unit is no longer sitting quietly, so no caller can leave it stuck.
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

    private static final float RAISE_STEP = 0.2F;

    private final EntityTraveller unit;
    private int sipsLeft;
    private int phaseTicks;
    // Server: what is left of this seat's snacks (true = a drink), and the wait before the next one.
    private final java.util.ArrayDeque<Boolean> planned = new java.util.ArrayDeque<>();
    private int nextDelay;
    // Server: the heal-eating task (EntityAIAutoEat) owns the shown item; the seated snack logic stands aside.
    private boolean meal;
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
        if (unit.getRNG().nextDouble() >= TravellersConfig.REST_SPOTS.snackChance) return;
        boolean drinkFirst = unit.getRNG().nextBoolean();
        planned.add(drinkFirst);
        if (unit.getRNG().nextInt(3) == 0) planned.add(!drinkFirst); // a third of the time, both
        nextDelay = between(FIRST_DELAY_MIN, FIRST_DELAY_MAX);
        TravellerTracer.event(unit, "snack", "planned " + planned + " (true = drink) in " + nextDelay / 20 + "s");
    }

    /** Server, for the heal-eating task: shows {@code food} at the mouth until {@link #releaseMeal}, seated or not. */
    public void holdForMeal(ItemStack food) {
        if (unit.world.isRemote) return;
        planned.clear();
        sipsLeft = 0;
        meal = true;
        unit.getDataManager().set(EntityTraveller.SNACK, food.copy());
        unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, true);
    }

    public void releaseMeal() {
        if (unit.world.isRemote || !meal) return;
        meal = false;
        unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, false);
        unit.getDataManager().set(EntityTraveller.SNACK, ItemStack.EMPTY);
    }

    /** Server: one chew of the shown item (sound, crumbs). */
    public void chew() {
        if (isActive()) sipEffects();
    }

    public void stop() {
        if (meal) return;
        planned.clear();
        if (unit.world.isRemote || !isActive()) return;
        TravellerTracer.event(unit, "snack", "interrupted");
        sipsLeft = 0;
        unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, false);
        unit.getDataManager().set(EntityTraveller.SNACK, ItemStack.EMPTY);
    }

    /** Server, every tick after the sitting pose's own tick. */
    void tick() {
        if (meal) return;
        if (!isActive()) {
            if (!planned.isEmpty()) tickPlanned();
            return;
        }
        if (!canSnackNow()) {
            stop();
            return;
        }
        if (isAtMouth()) {
            if (phaseTicks <= 25 && phaseTicks % 4 == 0) sipEffects();
            if (--phaseTicks > 0) return;
            unit.getDataManager().set(EntityTraveller.SNACK_AT_MOUTH, false);
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
            TravellerTracer.event(unit, "snack", "dropped: no longer sitting quietly");
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
        TravellerTracer.event(unit, "snack", snack.stack.getItem().getRegistryName() + " x" + sipsLeft);
        unit.getDataManager().set(EntityTraveller.SNACK, snack.stack.copy());
        beginSip();
    }

    private int between(int min, int max) {
        return min + unit.getRNG().nextInt(max - min + 1);
    }

    /** Client, every tick: eases the arm towards the synced pose. */
    void clientTick() {
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
        return unit.isEntityAlive() && unit.sit.isSitting() && unit.hurtTime == 0 && !unit.isInCombat()
                && unit.getAttackTarget() == null && !unit.isHandActive() && !unit.isAutoEating();
    }

    // Mirrors EntityLivingBase.updateItemUse: a gulp for a drink, crumbs and a chew for food.
    private void sipEffects() {
        ItemStack stack = getStack();
        if (isDrinking()) {
            unit.playSound(SoundEvents.ENTITY_GENERIC_DRINK, 0.5F, unit.world.rand.nextFloat() * 0.1F + 0.9F);
            return;
        }
        unit.playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.5F + 0.5F * unit.getRNG().nextInt(2),
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
