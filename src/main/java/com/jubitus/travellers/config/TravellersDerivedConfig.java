package com.jubitus.travellers.config;


import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.combat.lists.EntityRuleList;
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

    public static final EntityRuleList RAIDER_ATTACK_LIST = new EntityRuleList();
    public static final EntityRuleList TRAVELLER_ATTACK_LIST = new EntityRuleList();
    public static final EntityRuleList WORKER_ATTACK_LIST = new EntityRuleList();
    public static final EntityRuleList TRAVELLER_AGGRO_BLACKLIST = new EntityRuleList();
    public static final EntityRuleList RAIDER_AGGRO_BLACKLIST = new EntityRuleList();

    /**
     * Single place deciding whether Peaceful difficulty should suppress combat for this world.
     * Every peaceful-gated check in the mod must route through this instead of comparing
     * getDifficulty() itself, so COMBAT.ignorePeaceful flips all of them at once.
     */
    public static boolean isPeacefulBlocked(World world) {
        return world.getDifficulty() == net.minecraft.world.EnumDifficulty.PEACEFUL
                && !TravellersConfig.COMBAT.ignorePeaceful;
    }

    private static String raiderMarkColorSource;
    private static int raiderMarkColor;

    /** RENDERING.raiderMarkColor parsed as RGB; black when it isn't a valid hex colour. Re-parsed when the text changes. */
    public static int raiderMarkColor() {
        String text = TravellersConfig.RENDERING.raiderMarkColor;
        if (!java.util.Objects.equals(text, raiderMarkColorSource)) {
            int parsed = 0;
            try {
                String hex = text == null ? "" : text.trim();
                if (hex.startsWith("#")) hex = hex.substring(1);
                if (hex.length() == 6) parsed = Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
            }
            raiderMarkColor = parsed;
            raiderMarkColorSource = text;
        }
        return raiderMarkColor;
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

        com.jubitus.travellers.ground.BlockRules.clearCache();
        com.jubitus.travellers.village.CultureSnacks.clear();
    }


    /** Re-applies live-tunable attributes to every currently loaded EntityTraveller. */
    public static void applyRuntimeEffects() {
        MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (srv == null) return;

        if (TravellersConfig.COMBAT.rangedMaxDist < TravellersConfig.COMBAT.unitAttackTriggerRange) {
            TravellersConfig.COMBAT.rangedMaxDist = TravellersConfig.COMBAT.unitAttackTriggerRange;
        }

        for (World world : srv.worlds) {
            if (world == null) continue;

            for (EntityTraveller t : world.getEntities(EntityTraveller.class, e -> true)) {
                t.updateSizeForRenderScale();
                t.onConfigReload();
            }
        }
    }
}
