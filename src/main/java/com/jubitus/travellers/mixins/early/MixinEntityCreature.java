package com.jubitus.travellers.mixins;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's leash logic must never MOVE a bonded mount. {@code EntityCreature.updateLeashedState}
 * re-paths a leashed animal toward a point 2 blocks short of its holder <em>every tick</em> once it
 * is within 6 blocks, and adds spring impulses to its motion between 6 and 10 - all at the horse's
 * boosted MOVEMENT_SPEED attribute. That ran in parallel with {@code TravellerMountSupport}'s own
 * led-horse driving, three steering sources fighting over one body, which is exactly the "erratic
 * led horse" report: charge at the rider, overshoot, re-path, zigzag.
 *
 * <p>Cancelled wholesale for bonded mounts so {@code LedHorseDriver.driveLedHorse} is the
 * single driver; the leash itself stays attached and rendered (this method only steers, it never
 * detaches - the 10-block snap it would also do is already blocked by {@code MixinEntityLiving}).
 * What the cancel skips is all covered elsewhere: {@code tickMount} re-asserts leash state every
 * tick (so the NBT leash restore in the super call is redundant), and a dead rider releases the
 * leash through {@code releaseMountOnRiderDeath}.
 *
 * <p>Lives in the early config: EntityCreature is loaded during Minecraft's own entity class
 * scanning, long before the normal config registers (see CLAUDE.md's mixin section - a late
 * registration silently never applies).
 */
@Mixin(EntityCreature.class)
public abstract class MixinEntityCreature {

    // Diagnostic only (see travellers$diagnoseLeash below) - counts how many leashed
    // EntityCreatures actually reached vanilla's own repath branch (f<=6, the expensive one) this
    // window, split by whether the cancel above was supposed to have already stopped them. Reset
    // and logged once every 100 ticks (5s) so it costs nothing when MOUNTS.debugMounts is off and
    // stays readable when it's on, instead of one line per entity per tick.
    private static int diagBondedReachedBody = 0;
    private static int diagOtherLeashedNearRepath = 0;
    private static long diagWindowStartTick = Long.MIN_VALUE;

    @Inject(method = "updateLeashedState", at = @At("HEAD"), cancellable = true)
    private void travellers$keepVanillaOffBondedMounts(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        boolean bonded = HorseMarkers.isBondedMount(self);
        if (TravellersConfig.MOUNTS.debugMounts) {
            travellers$diagnoseLeash((EntityCreature) (Object) this, self, bonded);
        }
        if (bonded) {
            ci.cancel();
        }
    }

    /**
     * Confirms (rather than assumes) that the cancel above actually accounts for the vanilla leash
     * repath cost seen in a large mounted group's profile - {@code diagBondedReachedBody} must stay
     * at 0 every window; anything else means a bonded horse is somehow reaching vanilla's own
     * pathfinding despite the cancel. {@code diagOtherLeashedNearRepath} separately counts leashed
     * creatures that are NOT one of our bonded mounts but are close enough to their holder to be
     * about to pay for a real {@code tryMoveToXYZ} search - if that number is the one running high,
     * the cost is coming from something else entirely (a player's own leashed animal, a Stable pool
     * horse, another mod), not from this mod's mount system.
     */
    private static void travellers$diagnoseLeash(EntityCreature ec, Entity self, boolean bonded) {
        if (!ec.getLeashed() || ec.getLeashHolder() == null || ec.getLeashHolder().world != ec.world) return;
        float f = ec.getDistance(ec.getLeashHolder());
        if (f > 6.0F) return; // outside the branch that calls tryMoveToXYZ - not part of this cost

        if (bonded) {
            diagBondedReachedBody++;
        } else {
            diagOtherLeashedNearRepath++;
        }

        long now = ec.world.getTotalWorldTime();
        if (diagWindowStartTick == Long.MIN_VALUE) diagWindowStartTick = now;
        if (now - diagWindowStartTick >= 100) {
            if (diagBondedReachedBody > 0 || diagOtherLeashedNearRepath > 0) {
                FMLLog.log(Level.INFO,
                        "[Travellers/MountDiag] leash-repath window: bondedReachedVanillaBody=%d (should always be 0) "
                                + "otherLeashedNearRepath=%d (non-bonded leashed creatures within 6 blocks of their holder)",
                        diagBondedReachedBody, diagOtherLeashedNearRepath);
            }
            diagBondedReachedBody = 0;
            diagOtherLeashedNearRepath = 0;
            diagWindowStartTick = now;
        }
    }
}
