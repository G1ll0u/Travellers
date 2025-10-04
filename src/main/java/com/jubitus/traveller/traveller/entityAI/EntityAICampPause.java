package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.traveller.utils.blocks.CampfireBlock;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Items;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.Random;

public class EntityAICampPause extends EntityAIBase {
    // Tunables (keep small and simple)
    private static final int COOLDOWN_MIN = 60 * 20; // 60s
    private static final int COOLDOWN_MAX = 120 * 20; // 120s
    private static final int DURATION_MIN = 18 * 20; // 18s
    private static final int DURATION_MAX = 36 * 20; // 36s
    private static final int SCAN_RADIUS = 8;
    private final EntityTraveller mob;
    private final Random rand;
    private BlockPos campPos;
    private int campTicks;
    private int eatTicker;
    private int cooldownTicks = 0;
    private boolean placed;

    public EntityAICampPause(EntityTraveller mob) {
        this.mob = mob;
        this.rand = mob.getRNG();
        setMutexBits(1 | 2); // MOVE + LOOK: we own movement/head while camping
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false;
        if (mob.isAutoEating() || mob.isHandActive()) return false;
        if (mob.isFollowingSomeone()) return false;
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }

        // Only between villages: we require a travel target and not “near” any village
        if (mob.getTargetVillage() == null) return false;
        BlockPos near = mob.getNearestVillage();
        if (near != null && mob.getPosition().distanceSq(near) <= (EntityTraveller.VILLAGE_RADIUS * EntityTraveller.VILLAGE_RADIUS))
            return false;

        // low random chance per tick (about once per ~90s on average)
        if (rand.nextInt(90) != 0) return false;

        // need somewhere to place the pit
        BlockPos spot = pickCampSpotAhead();
        if (spot == null) return false;

        // need “food-looking” item to hold (any ItemFood in backpack or bread fallback)
        return findAnyFoodInBackpack() != ItemStack.EMPTY;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false;
        if (campPos == null) return false;
        return campTicks > 0;
    }

    @Override
    public void startExecuting() {
        this.campPos = pickCampSpotAhead();
        this.campTicks = DURATION_MIN + rand.nextInt(DURATION_MAX - DURATION_MIN + 1);
        this.eatTicker = 20 + rand.nextInt(40); // 1–3s to first nibble
        this.placed = false;

        // stop moving; face the camp spot
        PathNavigate nav = mob.getNavigator();
        if (nav != null) nav.clearPath();

        // place & light fire pit
        placeFireIfPossible();
    }

    @Override
    public void resetTask() {
        // clean up fire pit if we placed it
        if (placed && campPos != null && mob.world.getBlockState(campPos).getBlock() == CampfireBlock.get()) {
            mob.world.destroyBlock(campPos, false);
        }
        // stop any use animation and restore hand if we left food there
        if (mob.isHandActive()) mob.resetActiveHand();
        // Cooldown before next camp
        cooldownTicks = COOLDOWN_MIN + rand.nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        campPos = null;
        placed = false;
    }

    @Override
    public void updateTask() {
        // face the fire
        if (campPos != null) {
            double dx = (campPos.getX() + 0.5) - mob.posX;
            double dz = (campPos.getZ() + 0.5) - mob.posZ;
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            mob.rotationYaw = yaw;
            mob.renderYawOffset = yaw;
        }

        // “fake” eat occasionally (no regen, don’t consume)
        if (--eatTicker <= 0) {
            fakeEatOnce();
            eatTicker = 30 + rand.nextInt(80); // another nibble in ~1.5–5.5s
        }

        // occasionally glance around
        if (rand.nextInt(15) == 0) {
            // small idle head jitter
            mob.rotationYawHead = mob.rotationYaw + (rand.nextFloat() - 0.5f) * 20f;
        }

        campTicks--;
    }

    private void fakeEatOnce() {
        // hold a food item and play the use animation (don’t consume, no regen)
        ItemStack food = findAnyFoodInBackpack();
        if (food.isEmpty()) return;

        // briefly show in hand
        ItemStack prev = mob.getHeldItemMainhand();
        mob.setHeldItem(EnumHand.MAIN_HAND, food);
        mob.setActiveHand(EnumHand.MAIN_HAND);

        // quick bite: play vanilla eat sound and stop very soon
        mob.playSound(net.minecraft.init.SoundEvents.ENTITY_GENERIC_EAT, 0.7F, 0.9F + rand.nextFloat() * 0.2F);

        mob.resetActiveHand();
        mob.setHeldItem(EnumHand.MAIN_HAND, prev);
    }

    private void placeFireIfPossible() {
        if (campPos == null) return;
        if (!(mob.world.isAirBlock(campPos) && mob.world.isAirBlock(campPos.up()))) return;

        // solid ground?
        if (!mob.world.getBlockState(campPos.down()).isSideSolid(mob.world, campPos.down(), EnumFacing.UP)) return;

        // place + light
        IBlockState lit = CampfireBlock.litStateFacingYaw(mob.rotationYaw);
        if (lit != null) {
            mob.world.setBlockState(campPos, lit, 3);
            placed = true;
        }
    }

    private BlockPos pickCampSpotAhead() {
        // try a few positions in a small fan in front of the mob, inside loaded chunks
        final int tries = 8;
        double baseYaw = mob.rotationYaw;
        for (int i = 0; i < tries; i++) {
            double yaw = baseYaw + (rand.nextDouble() - 0.5) * 60.0; // ±30°
            double dist = 2.5 + rand.nextDouble() * 2.0;             // 2.5–4.5 blocks
            int x = (int) Math.floor(mob.posX + -Math.sin(Math.toRadians(yaw)) * dist);
            int z = (int) Math.floor(mob.posZ + Math.cos(Math.toRadians(yaw)) * dist);
            int y = mob.world.getHeight(new BlockPos(x, 0, z)).getY();
            BlockPos p = new BlockPos(x, y, z);
            if (isStandable(p) && mob.world.isAirBlock(p) && mob.world.isAirBlock(p.up())) {
                return p;
            }
        }
        return null;
    }

    private ItemStack findAnyFoodInBackpack() {
        IItemHandler inv = mob.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (inv == null) return ItemStack.EMPTY;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() instanceof ItemFood) {
                ItemStack one = s.copy();
                one.setCount(1);
                return one;
            }
        }
        // fallback visual food so camp still works
        return new ItemStack(Items.BREAD);
    }

    private boolean isStandable(BlockPos feet) {
        World w = mob.world;
        return w.getBlockState(feet.down()).isSideSolid(w, feet.down(), EnumFacing.UP)
                && w.getBlockState(feet).getMaterial().isReplaceable()
                && w.getBlockState(feet.up()).getMaterial().isReplaceable();
    }
}
