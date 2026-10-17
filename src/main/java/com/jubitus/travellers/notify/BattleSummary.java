package com.jubitus.travellers.notify;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured summary of a battle told to one owner, for the journal row's tooltip.
 * Kept as typed fields (not a pre-formatted string) so the client renders it in its own language.
 */
public final class BattleSummary {
    public final BlockPos location;
    public final int dimension;
    public final List<CasualtyEntry> ownWorkerCasualties;
    public final List<String> attackerDescriptions;

    public BattleSummary(BlockPos location, int dimension,
                          List<CasualtyEntry> ownWorkerCasualties,
                          List<String> attackerDescriptions) {
        this.location = location;
        this.dimension = dimension;
        this.ownWorkerCasualties = ownWorkerCasualties;
        this.attackerDescriptions = attackerDescriptions;
    }

    public static final class CasualtyEntry {
        public final String name;
        public final boolean died;
        public final float healthAfter;
        /** See {@link WorkerLifecycleNotifications#respawnOutlook}. */
        public final int respawnOutlook;

        public CasualtyEntry(String name, boolean died, float healthAfter, int respawnOutlook) {
            this.name = name;
            this.died = died;
            this.healthAfter = healthAfter;
            this.respawnOutlook = respawnOutlook;
        }
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("DimX", location.getX());
        tag.setInteger("DimY", location.getY());
        tag.setInteger("DimZ", location.getZ());
        tag.setInteger("Dimension", dimension);

        NBTTagList casualties = new NBTTagList();
        for (CasualtyEntry c : ownWorkerCasualties) {
            NBTTagCompound cTag = new NBTTagCompound();
            cTag.setString("Name", c.name);
            cTag.setBoolean("Died", c.died);
            cTag.setFloat("HealthAfter", c.healthAfter);
            cTag.setInteger("Respawn", c.respawnOutlook);
            casualties.appendTag(cTag);
        }
        tag.setTag("Casualties", casualties);

        NBTTagList attackers = new NBTTagList();
        for (String desc : attackerDescriptions) {
            attackers.appendTag(new NBTTagString(desc));
        }
        tag.setTag("Attackers", attackers);

        return tag;
    }

    public static BattleSummary fromNbt(NBTTagCompound tag) {
        BlockPos location = new BlockPos(tag.getInteger("DimX"), tag.getInteger("DimY"), tag.getInteger("DimZ"));
        int dimension = tag.getInteger("Dimension");

        List<CasualtyEntry> casualties = new ArrayList<>();
        NBTTagList casualtiesTag = tag.getTagList("Casualties", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < casualtiesTag.tagCount(); i++) {
            NBTTagCompound cTag = casualtiesTag.getCompoundTagAt(i);
            casualties.add(new CasualtyEntry(
                    cTag.getString("Name"), cTag.getBoolean("Died"), cTag.getFloat("HealthAfter"),
                    cTag.getInteger("Respawn")));
        }

        List<String> attackers = new ArrayList<>();
        NBTTagList attackersTag = tag.getTagList("Attackers", Constants.NBT.TAG_STRING);
        for (int i = 0; i < attackersTag.tagCount(); i++) {
            attackers.add(attackersTag.getStringTagAt(i));
        }

        return new BattleSummary(location, dimension, casualties, attackers);
    }
}
