package com.jubitus.travellers.config;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.combat.lists.AttackList;
import com.jubitus.travellers.combat.lists.TravellerBlacklist;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Runtime objects derived from TravellersConfig raw string arrays (attack/blacklist rule sets,
 * filters) plus small client-side caches of server-sent values.
 * Rebuilt via refresh(), called from the same startup step that used to call
 * TravellersModConfig.applyDerivedValues().
 */
public class TravellersDerivedConfig {

    public static final AttackList RAIDER_ATTACK_LIST = new AttackList();
    public static final AttackList TRAVELLER_ATTACK_LIST = new AttackList();
    public static final AttackList WORKER_ATTACK_LIST = new AttackList();
    // Never populated (no rule source ever calls reload() on it) — kept for behavioral parity
    // with the legacy TravellersDerivedConfig.TRAVELLER_BLACKLIST, which had the same gap.
    public static final TravellerBlacklist TRAVELLER_BLACKLIST = new TravellerBlacklist();
    public static final TravellerBlacklist TRAVELLER_AGGRO_BLACKLIST = new TravellerBlacklist();
    public static final TravellerBlacklist RAIDER_AGGRO_BLACKLIST = new TravellerBlacklist();

    // Client-only: last value received from server for GUI display. -1/NaN = unknown (not received yet).
    public static int workerHireCostDeniersServerCache = -1;
    public static int recruitFeeFlatServerCache = -1;
    public static double recruitFeePerBlockServerCache = Double.NaN;
    public static int recruitFeePerVillageServerCache = -1;

    /**
     * Single place deciding whether Peaceful difficulty should suppress combat for this world.
     * Every peaceful-gated check in the mod must route through this instead of comparing
     * getDifficulty() itself, so COMBAT.ignorePeaceful flips all of them at once.
     */
    public static boolean isPeacefulBlocked(World world) {
        return world.getDifficulty() == net.minecraft.world.EnumDifficulty.PEACEFUL
                && !TravellersConfig.COMBAT.ignorePeaceful;
    }

    public static void refresh() {
        RAIDER_ATTACK_LIST.reload(
                TravellersConfig.ATTACK_RULES.raiderAttackIdRules,
                TravellersConfig.ATTACK_RULES.raiderAttackClassRules);
        TRAVELLER_ATTACK_LIST.reload(
                TravellersConfig.ATTACK_RULES.travellerAttackIdRules,
                TravellersConfig.ATTACK_RULES.travellerAttackClassRules);
        WORKER_ATTACK_LIST.reload(
                TravellersConfig.ATTACK_RULES.workerAttackIdRules,
                TravellersConfig.ATTACK_RULES.workerAttackClassRules);
        TRAVELLER_AGGRO_BLACKLIST.reload(
                TravellersConfig.AGGRO_BLACKLIST.travellerAggroBlacklistIds,
                TravellersConfig.AGGRO_BLACKLIST.travellerAggroBlacklistClasses);
        RAIDER_AGGRO_BLACKLIST.reload(
                TravellersConfig.AGGRO_BLACKLIST.raiderAggroBlacklistIds,
                TravellersConfig.AGGRO_BLACKLIST.raiderAggroBlacklistClasses);

        com.jubitus.travellers.world.ground.BlockRules.clearCache();
    }

    public static void syncServerCostCachesFromConfig() {
        workerHireCostDeniersServerCache = TravellersConfig.WORKER.workerHireCostDeniers;
        recruitFeeFlatServerCache = TravellersConfig.RECRUITMENT.recruitFeeFlat;
        recruitFeePerBlockServerCache = TravellersConfig.RECRUITMENT.recruitFeePerBlock;
        recruitFeePerVillageServerCache = TravellersConfig.RECRUITMENT.recruitFeePerVillage;
    }

    /** Re-applies live-tunable attributes to every currently loaded EntityTraveller. */
    public static void applyRuntimeEffects() {
        MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (srv == null) return;

        if (TravellersConfig.COMBAT.rangedMaxDist < TravellersConfig.COMBAT.attackTriggerRange) {
            TravellersConfig.COMBAT.rangedMaxDist = TravellersConfig.COMBAT.attackTriggerRange;
        }

        for (World world : srv.worlds) {
            if (world == null) continue;

            for (EntityTraveller t : world.getEntities(EntityTraveller.class, e -> true)) {
                final double cfgMaxHealth;
                final double cfgFollowRange;

                // Per-type max health/follow range must match what each subclass's own
                // onConfigReload() will apply right below - otherwise a worker (workerMaxHealth,
                // e.g. 40) briefly gets clamped down to the generic travellerMaxHealth (e.g. 25)
                // here, then healed back up to 40 over time by natural regen once onConfigReload()
                // restores the correct max a few lines later.
                if (t instanceof EntityTravellerWorker) {
                    cfgMaxHealth = TravellersConfig.ATTRIBUTES.workerMaxHealth;
                    cfgFollowRange = TravellersConfig.ATTRIBUTES.workerFollowRange;
                } else if (t instanceof EntityTravellerRaider) {
                    cfgMaxHealth = TravellersConfig.ATTRIBUTES.raiderMaxHealth;
                    cfgFollowRange = TravellersConfig.ATTRIBUTES.travellerFollowRange;
                } else {
                    cfgMaxHealth = TravellersConfig.ATTRIBUTES.travellerMaxHealth;
                    cfgFollowRange = TravellersConfig.ATTRIBUTES.travellerFollowRange;
                }

                t.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(cfgMaxHealth);
                if (t.getHealth() > t.getMaxHealth()) {
                    t.setHealth(t.getMaxHealth());
                }

                t.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE)
                        .setBaseValue(TravellersConfig.ATTRIBUTES.attackDamage);
                t.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                        .setBaseValue(TravellersConfig.SPEED.movementSpeed);
                t.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(cfgFollowRange);
                t.updateSizeForRenderScale();

                t.onConfigReload();
            }
        }
    }
}
