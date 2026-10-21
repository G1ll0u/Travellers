package com.jubitus.travellers.raid;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.equipment.SummonLoadout;
import net.minecraft.inventory.EntityEquipmentSlot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** A wave kind's gear settings from the config; each raider draws its own tiers and role from them. */
final class RaidGear {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Raids");

    private final List<SummonLoadout.Gear> armour;
    private final List<SummonLoadout.Blade> weapon;
    private final int archerPercent;
    private final int shieldPercent;

    private RaidGear(List<SummonLoadout.Gear> armour, List<SummonLoadout.Blade> weapon,
                     int archerPercent, int shieldPercent) {
        this.armour = armour;
        this.weapon = weapon;
        this.archerPercent = archerPercent;
        this.shieldPercent = shieldPercent;
    }

    static RaidGear of(RaidPlan.WaveKind kind) {
        TravellersConfig.Raids c = TravellersConfig.RAIDS;
        TravellersConfig.Raids d = new TravellersConfig.Raids();
        switch (kind) {
            case ELITE:
                return of("elite", c.eliteArmor, d.eliteArmor, c.eliteWeapon, d.eliteWeapon,
                        c.eliteArcherPercent, c.eliteShieldPercent);
            case CAVALRY:
                return of("cavalry", c.cavalryArmor, d.cavalryArmor, c.cavalryWeapon, d.cavalryWeapon,
                        c.cavalryArcherPercent, c.cavalryShieldPercent);
            default:
                return of("horde", c.hordeArmor, d.hordeArmor, c.hordeWeapon, d.hordeWeapon,
                        c.hordeArcherPercent, c.hordeShieldPercent);
        }
    }

    private static RaidGear of(String name, String armour, String armourDefault, String weapon, String weaponDefault,
                               int archerPercent, int shieldPercent) {
        return new RaidGear(
                tiersOrDefault(name + " armour", armour, armourDefault, SummonLoadout.Gear.class, SummonLoadout.Gear.NATURAL),
                tiersOrDefault(name + " weapon", weapon, weaponDefault, SummonLoadout.Blade.class, SummonLoadout.Blade.NATURAL),
                archerPercent, shieldPercent);
    }

    void outfit(EntityTraveller t, @Nullable CultureDef c, Random rng) {
        boolean archer = rng.nextInt(100) < archerPercent;
        SummonLoadout.Gear gear = armour.get(rng.nextInt(armour.size()));
        SummonLoadout.Blade blade = weapon.get(rng.nextInt(weapon.size()));
        SummonLoadout.Choice shield = !archer && rng.nextInt(100) < shieldPercent
                ? SummonLoadout.Choice.YES : SummonLoadout.Choice.NO;
        SummonLoadout.apply(t, c, rng, CultureDef.WeightProfile.RAIDER, gear,
                archer ? SummonLoadout.Weapon.BOW : SummonLoadout.Weapon.MELEE, blade, shield);
        // NATURAL already went through the guaranteed armour of the spawn roll.
        if (gear == SummonLoadout.Gear.NONE && TravellersConfig.EQUIPMENT.raiderGuaranteedArmor) {
            SummonLoadout.wear(t, c, rng, CultureDef.WeightProfile.RAIDER, EntityEquipmentSlot.HEAD, SummonLoadout.Gear.LEATHER);
            SummonLoadout.wear(t, c, rng, CultureDef.WeightProfile.RAIDER, EntityEquipmentSlot.CHEST, SummonLoadout.Gear.LEATHER);
        }
    }

    private static <E extends Enum<E>> List<E> tiersOrDefault(String what, String text, String fallback,
                                                              Class<E> type, E natural) {
        List<E> tiers = tiers(text, type, natural);
        if (!tiers.isEmpty()) return tiers;
        LOGGER.warn("Raid {} '{}' is not a tier or a range, using '{}'", what, text, fallback);
        return tiers(fallback, type, natural);
    }

    /** The tiers "A", "A-B" or "B-A" covers in enum order; NATURAL only alone; empty when the text is invalid. */
    static <E extends Enum<E>> List<E> tiers(@Nullable String text, Class<E> type, E natural) {
        if (text == null) return Collections.emptyList();
        String[] parts = text.trim().toUpperCase(Locale.ROOT).split("\\s*-\\s*");
        if (parts.length > 2) return Collections.emptyList();
        E a = constant(type, parts[0]);
        E b = parts.length == 2 ? constant(type, parts[1]) : a;
        if (a == null || b == null) return Collections.emptyList();
        if (a == natural || b == natural) {
            return a == b ? Collections.singletonList(natural) : Collections.<E>emptyList();
        }
        E[] all = type.getEnumConstants();
        List<E> out = new ArrayList<>();
        for (int i = Math.min(a.ordinal(), b.ordinal()); i <= Math.max(a.ordinal(), b.ordinal()); i++) out.add(all[i]);
        return out;
    }

    @Nullable
    private static <E extends Enum<E>> E constant(Class<E> type, String name) {
        for (E e : type.getEnumConstants()) if (e.name().equals(name)) return e;
        return null;
    }
}
