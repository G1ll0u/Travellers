package com.jubitus.travellers.horse;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;

/**
 * A horse held as data: capture it, tell a corpse, and drop its identity before a respawn.
 */
public final class HorseSnapshots {

    private HorseSnapshots() {
    }

    /**
     * The horse as NBT, without its passenger list: a snapshot taken while ridden would otherwise
     * carry a full copy of the rider, which a rebuild would spawn as a second unit.
     */
    public static NBTTagCompound capture(EntityHorse horse) {
        NBTTagCompound nbt = new NBTTagCompound();
        horse.writeToNBT(nbt);
        nbt.removeTag("Passengers");
        return nbt;
    }

    /**
     * A snapshot taken during vanilla's death animation (no health left): never rebuilt, or the
     * horse would only re-enter its own death on a loop.
     */
    public static boolean isCorpse(NBTTagCompound nbt) {
        return !nbt.hasKey("Health", 99) || nbt.getFloat("Health") <= 0.0F;
    }

    /** Drops the UUID so a respawn never collides with a copy still registered in the world. */
    public static void stripIdentity(NBTTagCompound nbt) {
        nbt.removeTag("UUIDMost");
        nbt.removeTag("UUIDLeast");
    }
}
