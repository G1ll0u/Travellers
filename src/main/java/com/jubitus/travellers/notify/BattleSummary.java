package com.jubitus.travellers.notify;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured summary of a battle told to one owner, for the journal row's tooltip: headcounts, not names.
 * Kept as typed fields (not a pre-formatted string) so the client renders it in its own language.
 */
public final class BattleSummary {

    public enum State { RUNNING, WON, LOST, BROKEN_OFF }

    /** Headcounts seen from the owner's side, at the time of the beat. */
    public static final class Counts {
        public final int ownStanding, ownFallen;
        public final int foeStanding, foeFallen;
        /** Enemies by kind, fallen included. */
        public final int foeRaiders, foeTravellers, foeHenchmen;
        /** Seconds since the first blow. */
        public final int seconds;

        public Counts(int ownStanding, int ownFallen, int foeStanding, int foeFallen,
                      int foeRaiders, int foeTravellers, int foeHenchmen, int seconds) {
            this.ownStanding = ownStanding;
            this.ownFallen = ownFallen;
            this.foeStanding = foeStanding;
            this.foeFallen = foeFallen;
            this.foeRaiders = foeRaiders;
            this.foeTravellers = foeTravellers;
            this.foeHenchmen = foeHenchmen;
            this.seconds = seconds;
        }

        void write(NBTTagCompound tag) {
            tag.setIntArray("Counts", new int[]{ownStanding, ownFallen, foeStanding, foeFallen,
                    foeRaiders, foeTravellers, foeHenchmen, seconds});
        }

        @Nullable
        static Counts read(NBTTagCompound tag) {
            int[] c = tag.getIntArray("Counts");
            return c.length < 8 ? null : new Counts(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
        }
    }

    public final BlockPos location;
    public final int dimension;
    public final List<CasualtyEntry> ownWorkerCasualties;
    /** Null on a journal row written before headcounts were kept. */
    @Nullable public final Counts counts;
    public final State state;
    /** Foe names of a journal row written before headcounts were kept; empty otherwise. */
    public final List<String> legacyAttackers;

    public BattleSummary(BlockPos location, int dimension, List<CasualtyEntry> ownWorkerCasualties,
                         @Nullable Counts counts, State state) {
        this(location, dimension, ownWorkerCasualties, counts, state, new ArrayList<>());
    }

    private BattleSummary(BlockPos location, int dimension, List<CasualtyEntry> ownWorkerCasualties,
                          @Nullable Counts counts, State state, List<String> legacyAttackers) {
        this.location = location;
        this.dimension = dimension;
        this.ownWorkerCasualties = ownWorkerCasualties;
        this.counts = counts;
        this.state = state;
        this.legacyAttackers = legacyAttackers;
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
        if (counts != null) counts.write(tag);
        tag.setByte("State", (byte) state.ordinal());
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
        State[] states = State.values();
        int s = tag.getByte("State");
        return new BattleSummary(location, dimension, casualties, Counts.read(tag),
                s >= 0 && s < states.length ? states[s] : State.RUNNING, attackers);
    }
}
