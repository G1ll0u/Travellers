package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;

public class EntityAIAutoEat extends EntityAIBase {
    private final EntityTraveller mob;
    private final float healthFrac;   // start eating below this fraction of max HP
    private final int postCombatDelayMin, postCombatDelayMax;   // ticks
    private final int betweenBiteDelayMin, betweenBiteDelayMax; // ticks
    private final FoodSpec[] foods;   // pool to pick from
    private final int postCombatDelay = 0;
    private final boolean wasInCombat = false;
    // runtime
    private int ticksLeftThisBite = 0;
    private int betweenBiteDelay = 0;
    private ItemStack savedMainHand = ItemStack.EMPTY;
    private FoodSpec currentFood = null;

    // fields
    private boolean recoverToFull = false;   // keep eating (with pauses) until full
    // New: absolute lock until world time (ticks). While now < eatLockUntilTick, no eating.
    private long eatLockUntilTick = 0L;

    public EntityAIAutoEat(EntityTraveller m, float healthFrac) {
        this.mob = m;
        this.healthFrac = healthFrac;


        this.postCombatDelayMin = 30;
        this.postCombatDelayMax = 50;
        this.betweenBiteDelayMin = 12;
        this.betweenBiteDelayMax = 30;

        // A small curated menu. heal = half-hearts (1.0F = 1/2 heart)
        this.foods = new FoodSpec[]{
                new FoodSpec(Items.BREAD, 18F, 64),
                new FoodSpec(Items.CARROT, 12F, 32),
                new FoodSpec(Items.COOKIE, 14F, 32),
                new FoodSpec(Items.BAKED_POTATO, 16F, 64),
                new FoodSpec(Items.COOKED_BEEF, 24.0F, 96), // 4 hearts
                new FoodSpec(Items.COOKED_CHICKEN, 24.0F, 120)
        };

        // lock movement & look while chewing
        this.setMutexBits(1 /*MOVE*/ | 2 /*LOOK*/);
    }

    @Override
    public boolean shouldExecute() {
        // If any threat is active *now*, push the lock forward and bail
        if (threatActive()) {
            blockEatingForPostCombat();
            return false;
        }

        // Respect the lock set during/after combat
        if (now() < eatLockUntilTick) return false;

        // Respect small pause between bites
        if (betweenBiteDelay > 0) {
            betweenBiteDelay--;
            return false;
        }

        // Must be idle to eat (keeps animation nice)
        if (!mob.getNavigator().noPath()) return false;

        // Healing need + session logic (unchanged)
        if (!isBelowFull(0.001F)) {
            recoverToFull = false;
            return false;
        }

        float max = (float) mob.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).getAttributeValue();
        float hp = mob.getHealth();
        boolean underThreshold = hp <= max * healthFrac;

        return underThreshold || recoverToFull;
    }

    /**
     * Returns true if we detect combat/threat signals this tick.
     */
    private boolean threatActive() {
        // isInCombat: your own signal
        if (mob.isInCombat()) return true;

        // vanilla signals
        if (mob.getRevengeTarget() != null) return true; // something recently attacked us
        if (mob.hurtTime > 0) return true;               // we were just damaged

        // you already use this later, but including it here helps seed the lock too
        return hasNearbyHostiles();
    }

    /**
     * Start a fresh post-combat lock window.
     */
    private void blockEatingForPostCombat() {
        eatLockUntilTick = now() + randBetween(postCombatDelayMin, postCombatDelayMax);
    }

    private long now() {
        return mob.world.getTotalWorldTime();
    }

    // === AI lifecycle ===

    private boolean isBelowFull(float epsilon) {
        return mob.getHealth() < mob.getMaxHealth() - epsilon;
    }

    private boolean hasNearbyHostiles() {
        AxisAlignedBB box = mob.getEntityBoundingBox().grow(12.0, 6.0, 12.0);
        return !mob.world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> e instanceof net.minecraft.entity.monster.IMob && e.isEntityAlive()).isEmpty();
    }

    private int randBetween(int min, int max) {
        return min + mob.getRNG().nextInt((max - min) + 1);
    }

    @Override
    public boolean shouldContinueExecuting() {
        // keep chewing as long as: still eating OR needs another bite and safe & idle
        return ticksLeftThisBite > 0;

        // no active bite → if still low HP and safe, we’ll schedule next bite via betweenBiteDelay
    }

    @Override
    public void startExecuting() {
        if (ticksLeftThisBite <= 0)
            recoverToFull = true;  // commit to healing to full until we actually reach it
        beginNewBite(); // start a new bite if not already
    }

    // === helpers ===

    @Override
    public void resetTask() {
        // called when AI ends for any reason
        finishHoldItem();
        currentFood = null;
        ticksLeftThisBite = 0;
    }

    @Override
    public void updateTask() {
        if (mob.isInCombat() || hasNearbyHostiles()) {
            cancelChew();
            return;
        }

        if (ticksLeftThisBite > 0) {
            // chew tick
            if (ticksLeftThisBite % 7 == 0) {
                mob.playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.3F, 0.8F + mob.getRNG().nextFloat() * 0.2F);
                spawnItemCrackAtMouth(currentFood.item);
            }
            // gradual healing per tick (no potion particles)
            float healPerTick = currentFood.healAmount / Math.max(1, currentFood.durationTicks);
            if (healPerTick > 0f) {
                float before = mob.getHealth();
                mob.heal(healPerTick);
                // stop early if we reach max HP mid-bite (feels snappy)
                if (mob.getHealth() >= mob.getMaxHealth() - 0.001F && before < mob.getMaxHealth()) {
                    ticksLeftThisBite = 1; // finish this tick then end
                }
            }
            // stop early if we reached max HP
            if (!isBelowFull(0.001F)) {
                ticksLeftThisBite = 1;
            }

            if (--ticksLeftThisBite <= 0) {
                finishHoldItem();
                currentFood = null;

                if (isBelowFull(0.001F)) {
                    // still not full → keep the session going
                    betweenBiteDelay = randBetween(betweenBiteDelayMin, betweenBiteDelayMax);
                } else {
                    // session complete
                    recoverToFull = false;
                }
            }
        }
    }

    private void cancelChew() {
        ticksLeftThisBite = 0;
        finishHoldItem();
        currentFood = null;

        // Add this line:
        blockEatingForPostCombat();

        // And keep the small between-bite pause; it feels natural once the lock expires
        betweenBiteDelay = randBetween(betweenBiteDelayMin, betweenBiteDelayMax);
    }

    private void spawnItemCrackAtMouth(Item item) {
        double x = mob.posX;
        double y = mob.posY + mob.getEyeHeight() * 0.85D;
        double z = mob.posZ;

        double spread = 0.2D;
        double speed = 0.02D;

        int itemId = net.minecraft.item.Item.getIdFromItem(item);
        int meta = 0;

        if (mob.world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) mob.world;
            ws.spawnParticle(
                    net.minecraft.util.EnumParticleTypes.ITEM_CRACK,
                    x, y, z,
                    6, // count
                    spread, spread, spread,
                    speed,
                    itemId, meta
            );
        } else {
            // client fallback
            for (int i = 0; i < 6; i++) {
                double mx = (mob.getRNG().nextDouble() - 0.5D) * speed * 2.0D;
                double my = (mob.getRNG().nextDouble() - 0.3D) * speed * 2.0D;
                double mz = (mob.getRNG().nextDouble() - 0.5D) * speed * 2.0D;
                mob.world.spawnParticle(
                        net.minecraft.util.EnumParticleTypes.ITEM_CRACK,
                        x, y, z,
                        mx, my, mz,
                        itemId
                );
            }
        }
    }

    private void finishHoldItem() {
        mob.resetActiveHand();
        // Always clear the main hand after eating/cancel
        mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        savedMainHand = ItemStack.EMPTY; // keep field tidy
        mob.getNavigator().clearPath();
    }

    private void beginNewBite() {
        currentFood = foods[mob.getRNG().nextInt(foods.length)];
        ticksLeftThisBite = currentFood.durationTicks;

        mob.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(currentFood.item));
        mob.setActiveHand(EnumHand.MAIN_HAND);
        mob.getNavigator().clearPath();
    }


    public boolean isEating() {
        return ticksLeftThisBite > 0 || mob.isHandActive();
    }

    public void cancel() { // external force-stop (from EntityTraveller)
        if (ticksLeftThisBite > 0 || mob.isHandActive()) cancelChew();
    }

    // simple food spec
    private static class FoodSpec {
        final net.minecraft.item.Item item;
        final float healAmount;    // in half-hearts (1.0F = 1/2 heart)
        final int durationTicks;   // how long this bite takes

        FoodSpec(net.minecraft.item.Item item, float healAmount, int durationTicks) {
            this.item = item;
            this.healAmount = Math.max(0F, healAmount);
            this.durationTicks = Math.max(6, durationTicks);
        }
    }
}




