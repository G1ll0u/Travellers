package com.jubitus.travellers.village;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.item.ItemStack;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.culture.VillageType;
import org.millenaire.common.world.UserProfile;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A player's standing with a Millénaire culture (its own reputation scale, -640..4096) and the culture's names. */
public final class MillCultures {

    /** Millénaire's "Stellar" culture reputation, the top of its ladder. */
    public static final int STELLAR = 2048;

    private MillCultures() {
    }

    /** Server side; 0 (Neutral) when Millénaire has no record of the player. */
    public static int reputation(WorldServer world, UUID playerId, String cultureKey) {
        UserProfile profile = MillSettlements.profile(world, playerId);
        return profile == null ? 0 : profile.getCultureReputation(cultureKey);
    }

    /** "Norman", in the client's Millénaire language; the key itself when the culture is unknown. */
    @SideOnly(Side.CLIENT)
    public static String adjective(String cultureKey) {
        Culture c = Culture.getCultureByName(cultureKey);
        return c != null ? c.getAdjectiveTranslated() : cultureKey;
    }

    /** Millénaire's own label for the local player's standing ("Neutral", "Good"…). */
    @SideOnly(Side.CLIENT)
    public static String localReputationLabel(String cultureKey) {
        Culture c = Culture.getCultureByName(cultureKey);
        return c != null ? c.getLocalPlayerReputationString() : "";
    }

    /** Millénaire's label for a village reputation ("Respected"…), empty when unknown. */
    @SideOnly(Side.CLIENT)
    public static String villageReputationLabel(String cultureKey, int reputation) {
        Culture c = Culture.getCultureByName(cultureKey);
        return c != null ? c.getReputationLevelLabel(reputation) : "";
    }

    /** The village or lone-building type's name in the client's language; empty when it has no real translation. */
    @SideOnly(Side.CLIENT)
    public static String settlementTypeName(String cultureKey, String typeKey, boolean lone) {
        VillageType t = settlementType(cultureKey, typeKey, lone);
        String name = t != null ? t.getNameTranslated() : null;
        // Millénaire hands back the lookup key itself ("village.japanfort") when the string is missing.
        return name == null || name.isEmpty() || name.equalsIgnoreCase("village." + typeKey) ? "" : name;
    }

    /**
     * The item Millénaire shows for this settlement type ({@code icon=} in its file); without one, its culture's most
     * used village icon, then its most used lone-building icon; null when the culture has none at all.
     */
    @SideOnly(Side.CLIENT)
    @Nullable
    public static ItemStack settlementIcon(String cultureKey, String typeKey, boolean lone) {
        ItemStack own = iconOf(settlementType(cultureKey, typeKey, lone));
        if (own != null) return own;
        if (!CULTURE_ICONS.containsKey(cultureKey)) CULTURE_ICONS.put(cultureKey, usualIcon(Culture.getCultureByName(cultureKey)));
        return CULTURE_ICONS.get(cultureKey);
    }

    // Culture key -> its usual settlement icon (null value: none). Culture files are read once, at startup.
    private static final Map<String, ItemStack> CULTURE_ICONS = new HashMap<>();

    @Nullable
    private static ItemStack usualIcon(@Nullable Culture c) {
        if (c == null) return null;
        ItemStack icon = mostUsedIcon(c.listVillageTypes);
        return icon != null ? icon : mostUsedIcon(c.listLoneBuildingTypes);
    }

    @Nullable
    private static ItemStack mostUsedIcon(List<VillageType> types) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, ItemStack> stacks = new HashMap<>();
        ItemStack best = null;
        int bestCount = 0;
        for (VillageType t : types) {
            ItemStack icon = iconOf(t);
            if (icon == null) continue;
            String key = icon.getItem().getRegistryName() + "@" + icon.getMetadata();
            int n = counts.merge(key, 1, Integer::sum);
            stacks.putIfAbsent(key, icon);
            if (n > bestCount) {
                bestCount = n;
                best = stacks.get(key);
            }
        }
        return best;
    }

    @Nullable
    private static ItemStack iconOf(@Nullable VillageType t) {
        ItemStack icon = t != null ? t.getIcon() : null;
        return icon != null && !icon.isEmpty() ? icon : null;
    }

    @Nullable
    private static VillageType settlementType(String cultureKey, String typeKey, boolean lone) {
        Culture c = Culture.getCultureByName(cultureKey);
        if (c == null) return null;
        return lone ? c.getLoneBuildingType(typeKey) : c.getVillageType(typeKey);
    }
}
