package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.GroupSuccession;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.item.ItemStack;

import java.util.Random;
import java.util.UUID;

/** What a unit is worth, from its gear: the coins and experience it drops when slain, its hiring price. */
public final class UnitWorth {

    private UnitWorth() {
    }

    public static int hirePrice(EntityTraveller unit) {
        return hirePrice(gearScore(unit), unit.getUniqueID());
    }

    public static int hirePrice(VirtualTravellerRecord record) {
        return hirePrice(GroupSuccession.gearScore(record), record.uuid);
    }

    private static int hirePrice(double gear, UUID id) {
        TravellersConfig.Money m = TravellersConfig.MONEY;
        // Rolled from the UUID so a worker keeps its price, loaded or virtual, across restarts.
        double roll = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits()).nextDouble() * 2.0D - 1.0D;
        double price = m.hireBaseDeniers + m.hireDeniersPerGearPoint * gear;
        return Math.max(0, (int) Math.round(price * (1.0D + roll * m.hireRandomPercent / 100.0D)));
    }

    /**
     * Coins at the feet of a slain unit a player hit recently (vanilla's rule for experience); a hired
     * worker's own death drops none.
     */
    public static void dropCoins(EntityTraveller unit, double gear, boolean hitByPlayer) {
        if (!hitByPlayer || unit.isHiredFollower() || !unit.world.getGameRules().getBoolean("doMobLoot")) return;
        TravellersConfig.Money m = TravellersConfig.MONEY;
        double amount = m.dropBaseDeniers + m.dropDeniersPerGearPoint * gear;
        int coins = (int) Math.round(scaled(unit, amount, m.dropRandomPercent));
        for (ItemStack stack : MillMoney.coins(coins)) unit.entityDropItem(stack, 0.5F);
    }

    /** Experience for a slain unit: its gear and its health above a player's. */
    public static int experience(EntityTraveller unit, double gear) {
        TravellersConfig.Money m = TravellersConfig.MONEY;
        double xp = m.xpBase + m.xpPerGearPoint * gear + Math.max(0.0D, (unit.getMaxHealth() - 20.0D) / 10.0D);
        return (int) Math.round(scaled(unit, xp, m.xpRandomPercent));
    }

    // Peaceful units are worth a share of a raider; then the random spread.
    private static double scaled(EntityTraveller unit, double amount, int randomPercent) {
        if (!(unit instanceof EntityTravellerRaider)) amount *= TravellersConfig.MONEY.peacefulDropPercent / 100.0D;
        return Math.max(0.0D, amount * (1.0D + (unit.getRNG().nextDouble() * 2.0D - 1.0D) * randomPercent / 100.0D));
    }

    public static double gearScore(EntityTraveller unit) {
        return GroupSuccession.gearScore(unit);
    }
}
