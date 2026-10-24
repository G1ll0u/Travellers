package com.jubitus.travellers.village;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.world.UserProfile;

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
}
