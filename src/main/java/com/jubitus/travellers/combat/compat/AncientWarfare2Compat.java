package com.jubitus.travellers.combat.compat;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Optional-dependency compat with Ancient Warfare 2's NPC module (faction NPCs like the
 * Empire/Bandits). AW2 is a real Gradle dependency (compiled against directly), but every
 * single one of its types is resolved purely via reflection here, exactly like the other
 * optional integrations in this codebase ({@code TravellerHostilityHelper.isLavaCowAggressive},
 * {@code MPBIntegration}) - never a direct import/instanceof/cast anywhere else in the mod.
 * That matters even though AW2 sits on the compile classpath: a direct type reference in an
 * always-loaded class (e.g. EntityTraveller) would force the JVM to resolve that AW2 class the
 * moment the referencing class is verified, crashing the mod for anyone without AW2 installed.
 * Reflection defers that resolution entirely to this class, guarded by {@link #PRESENT}.
 * <p>
 * AW2 factions have a real per-player reputation system (revenge + persisted standing,
 * {@code FactionTracker}/{@code FactionData}), not a static "this class is hostile" flag - a
 * faction like the Empire is neutral until provoked, others (bandits, demons) default hostile.
 * {@link #isHostile(Entity, EntityPlayer)} mirrors that: a hired follower's owner stands in for
 * "the player" ({@code NpcFaction.isHostileTowards(EntityPlayer)}, AW2's own player-hostility
 * check, covers both revenge and standing); an ownerless traveller/raider has no reputation to
 * check, so it falls back to the faction's static default standing from its json config.
 */
public final class AncientWarfare2Compat {

    private static final String MOD_ID = "ancientwarfarenpc";

    public static final boolean PRESENT = detect();

    private static Class<?> npcFactionClass;
    private static Method getFactionMethod;               // NpcFaction#getFaction() -> String
    private static Method isHostileTowardsMethod;          // NpcFaction#isHostileTowards(Entity) -> boolean
    private static Method registryGetFactionMethod;        // FactionRegistry#getFaction(String) -> FactionDefinition
    private static Method getStandingSettingsMethod;       // FactionDefinition#getStandingSettings() -> StandingSettings
    private static Method getPlayerDefaultStandingMethod;  // StandingSettings#getPlayerDefaultStanding() -> int

    private AncientWarfare2Compat() {
    }

    private static boolean detect() {
        if (!Loader.isModLoaded(MOD_ID)) return false;
        try {
            npcFactionClass = Class.forName("net.shadowmage.ancientwarfare.npc.entity.faction.NpcFaction");
            Class<?> factionRegistryClass = Class.forName("net.shadowmage.ancientwarfare.npc.registry.FactionRegistry");
            Class<?> factionDefClass = Class.forName("net.shadowmage.ancientwarfare.npc.registry.FactionDefinition");
            Class<?> standingSettingsClass = Class.forName("net.shadowmage.ancientwarfare.npc.registry.FactionDefinition$StandingSettings");

            getFactionMethod = npcFactionClass.getMethod("getFaction");
            isHostileTowardsMethod = npcFactionClass.getMethod("isHostileTowards", Entity.class);
            registryGetFactionMethod = factionRegistryClass.getMethod("getFaction", String.class);
            getStandingSettingsMethod = factionDefClass.getMethod("getStandingSettings");
            getPlayerDefaultStandingMethod = standingSettingsClass.getMethod("getPlayerDefaultStanding");
            return true;
        } catch (Throwable t) {
            // AW2 not present, or a different/incompatible version - stay disabled.
            npcFactionClass = null;
            return false;
        }
    }

    /** True if the entity is an AW2 faction NPC (Empire/Bandits/etc., not a player-owned unit). */
    public static boolean isFactionNpc(@Nullable Entity e) {
        return PRESENT && e != null && npcFactionClass.isInstance(e);
    }

    /**
     * Reputation-aware hostility check, shared by both directions (this class's caller deciding
     * whether to attack, and the AW2-side Mixin deciding the reverse - see
     * {@code MixinNpcFactionHostility}). Returns false outright if AW2 isn't installed, the
     * entity isn't an AW2 faction NPC, or the master config toggle is off.
     *
     * @param npcEntity      the candidate AW2 faction NPC (checked via reflection).
     * @param effectiveOwner the player whose reputation to check (a hired worker's owner), or
     *                       null for an ownerless traveller/raider - falls back to the faction's
     *                       static default standing.
     */
    public static boolean isHostile(@Nullable Entity npcEntity, @Nullable EntityPlayer effectiveOwner) {
        if (!isFactionNpc(npcEntity)) return false;
        if (!TravellersConfig.COMPAT.ancientWarfare2ReciprocalHostility) return false;

        // Already actively fighting my owner right now - treat as hostile regardless of AW2's
        // own reputation bookkeeping. AW2's faction "call for help"/alert reaction (nearby
        // NPCs joining a fight against whoever hit one of their own) sets an NPC's live
        // attackTarget/revengeTarget directly, without necessarily going through
        // isHostileTowards or updating persisted standing first - so a bystander NPC that just
        // joined the fight against the owner would otherwise still read as "not hostile" here
        // until the reputation catches up, which is what made only the NPC actually hit react.
        if (effectiveOwner != null && npcEntity instanceof EntityLiving) {
            EntityLiving npc = (EntityLiving) npcEntity;
            if (npc.getAttackTarget() == effectiveOwner || npc.getRevengeTarget() == effectiveOwner) {
                debugLog(npcEntity, effectiveOwner, true, "already engaged with owner");
                return true;
            }
        }

        try {
            if (effectiveOwner != null) {
                boolean result = (Boolean) isHostileTowardsMethod.invoke(npcEntity, effectiveOwner);
                debugLog(npcEntity, effectiveOwner, result, "isHostileTowards(owner)");
                return result;
            }
            String factionName = (String) getFactionMethod.invoke(npcEntity);
            Object factionDef = registryGetFactionMethod.invoke(null, factionName);
            Object standingSettings = getStandingSettingsMethod.invoke(factionDef);
            int defaultStanding = (Integer) getPlayerDefaultStandingMethod.invoke(standingSettings);
            boolean result = defaultStanding < 0;
            debugLog(npcEntity, null, result, "ownerless, faction default standing=" + defaultStanding);
            return result;
        } catch (Throwable t) {
            debugLog(npcEntity, effectiveOwner, false, "exception: " + t);
            return false;
        }
    }

    private static void debugLog(Entity npcEntity, @Nullable EntityPlayer effectiveOwner, boolean result, String reason) {
        if (!TravellersConfig.COMPAT.ancientWarfare2DebugLog) return;
        FMLLog.log(Level.INFO, "[Travellers/AW2] isHostile(npc=%s#%s, owner=%s) = %b (%s)",
                npcEntity.getName(), npcEntity.getUniqueID(),
                effectiveOwner != null ? effectiveOwner.getName() : "null",
                result, reason);
    }
}
