package com.jubitus.travellers.camp;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.camp.shelter.TerrainSnapshot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A discovered campsite location. The center anchors a firepit; {@link #slots} are the
 * pre-validated 5×5 shelter spots within {@link #radius}. Slot count is computed once the
 * footprint is loaded and persisted, then re-validated against terrain at materialization.
 *
 * Occupation state (which slot a traveller uses, build progress, stay timers) is added in a later
 * phase; this class is the persistent anchor it hangs off.
 */
public class CampsiteRecord {

    public final UUID id;
    public final BlockPos center;
    public final int dimension;
    public final int radius;

    /** Ground-level firepit position (center column). */
    public BlockPos firepitPos;

    public final List<ShelterSlot> slots = new ArrayList<>();

    /** True once slots have been computed from loaded terrain at least once. */
    public boolean slotsComputed = false;

    // --- Occupation (P3) ---
    /** slotIndex -> occupant. Source of truth for who is camping where. */
    public final Map<Integer, CampOccupant> occupants = new HashMap<>();
    /** Shelters/firepit whose occupant left while chunks were unloaded; restored at next reconcile. */
    public final List<TerrainSnapshot> pendingTeardowns = new ArrayList<>();
    /** slotIndex -> snapshot of an abandoned (dead-occupant) shelter that lingers as a ruin until the slot is reused or the camp is fully cleaned. */
    public final Map<Integer, TerrainSnapshot> orphans = new HashMap<>();
    public boolean firepitBuilt = false;
    @Nullable public TerrainSnapshot firepitSnapshot;
    /** False once an abandoned (all occupants dead/gone) camp's fire has gone out. Relit by the next group's fire-lighting ritual. */
    public boolean firepitLit = true;
    /** World tick when the camp last became empty while its firepit was still built; -1 while occupied. */
    public long emptySinceTick = -1L;

    /** "TRAVELLER", "WORKER" or "RAIDER" of current occupants, or null if empty. A camp is faction-locked while occupied. */
    @Nullable
    public String faction() {
        for (CampOccupant o : occupants.values()) return o.entityClass;
        return null;
    }

    /** Culture of the current occupants (first occupant's), or null if empty. */
    @Nullable
    public String culture() {
        for (CampOccupant o : occupants.values()) return o.cultureId;
        return null;
    }

    /** Raiders never mix with travellers/workers; travellers and non-hired workers share one "civil" faction. */
    private static boolean isCivilian(String entityClass) {
        return "TRAVELLER".equals(entityClass) || "WORKER".equals(entityClass);
    }

    private static boolean sameFaction(String a, String b) {
        return a.equals(b) || (isCivilian(a) && isCivilian(b));
    }

    /**
     * True if {@code entityClass} (of the given {@code cultureId}) may claim here: camp empty, or
     * same faction (civilian camps mix travellers+workers; raider camps stay raider-only) and a slot
     * is free. When {@link com.jubitus.travellers.config.TravellersConfig.CampEvents#campCultureRestrictionEnabled}
     * is on, also requires matching culture — unless the claimant is a raider and
     * {@code raidersIgnoreCulture} is on.
     */
    public boolean canClaim(String entityClass, @Nullable String cultureId) {
        if (firstFreeSlot() < 0) return false;
        String f = faction();
        if (f != null && !sameFaction(f, entityClass)) return false;

        boolean isRaider = "RAIDER".equals(entityClass);
        boolean cultureIgnored = isRaider && com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.raidersIgnoreCulture;
        if (com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.campCultureRestrictionEnabled && !cultureIgnored) {
            String c = culture();
            if (c != null && !c.equals(cultureId)) return false;
        }
        return true;
    }

    /** Closest unoccupied, non-disabled slot to the firepit, or -1 if full. */
    public int firstFreeSlot() {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            ShelterSlot s = slots.get(i);
            if (s.disabled) continue;
            if (occupants.containsKey(i)) continue;
            double d = s.center().distanceSq(firepitPos);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    public CampsiteRecord(UUID id, BlockPos center, int dimension, int radius) {
        this.id = id;
        this.center = center;
        this.dimension = dimension;
        this.radius = radius;
        this.firepitPos = center;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("Id", id);
        tag.setInteger("CX", center.getX());
        tag.setInteger("CY", center.getY());
        tag.setInteger("CZ", center.getZ());
        tag.setInteger("Dim", dimension);
        tag.setInteger("Rad", radius);
        tag.setInteger("FX", firepitPos.getX());
        tag.setInteger("FY", firepitPos.getY());
        tag.setInteger("FZ", firepitPos.getZ());
        tag.setBoolean("SC", slotsComputed);
        NBTTagList slotList = new NBTTagList();
        for (ShelterSlot s : slots) slotList.appendTag(s.toNbt());
        tag.setTag("Slots", slotList);

        NBTTagList occList = new NBTTagList();
        for (CampOccupant o : occupants.values()) occList.appendTag(o.toNbt());
        tag.setTag("Occ", occList);
        NBTTagList pend = new NBTTagList();
        for (TerrainSnapshot s : pendingTeardowns) pend.appendTag(s.toNbt());
        tag.setTag("Pend", pend);
        NBTTagList orph = new NBTTagList();
        for (Map.Entry<Integer, TerrainSnapshot> e : orphans.entrySet()) {
            NBTTagCompound o = e.getValue().toNbt();
            o.setInteger("Slot", e.getKey());
            orph.appendTag(o);
        }
        tag.setTag("Orph", orph);
        tag.setBoolean("FB", firepitBuilt);
        if (firepitSnapshot != null) tag.setTag("FS", firepitSnapshot.toNbt());
        tag.setBoolean("FL", firepitLit);
        tag.setLong("EST", emptySinceTick);
        return tag;
    }

    public static CampsiteRecord fromNbt(NBTTagCompound tag) {
        UUID id = tag.getUniqueId("Id");
        BlockPos center = new BlockPos(tag.getInteger("CX"), tag.getInteger("CY"), tag.getInteger("CZ"));
        CampsiteRecord r = new CampsiteRecord(id, center, tag.getInteger("Dim"), tag.getInteger("Rad"));
        r.firepitPos = new BlockPos(tag.getInteger("FX"), tag.getInteger("FY"), tag.getInteger("FZ"));
        r.slotsComputed = tag.getBoolean("SC");
        NBTTagList slotList = tag.getTagList("Slots", 10);
        for (int i = 0; i < slotList.tagCount(); i++) {
            r.slots.add(ShelterSlot.fromNbt(slotList.getCompoundTagAt(i)));
        }
        NBTTagList occList = tag.getTagList("Occ", 10);
        for (int i = 0; i < occList.tagCount(); i++) {
            CampOccupant o = CampOccupant.fromNbt(occList.getCompoundTagAt(i));
            r.occupants.put(o.slotIndex, o);
        }
        NBTTagList pend = tag.getTagList("Pend", 10);
        for (int i = 0; i < pend.tagCount(); i++) {
            r.pendingTeardowns.add(TerrainSnapshot.fromNbt(pend.getCompoundTagAt(i)));
        }
        NBTTagList orph = tag.getTagList("Orph", 10);
        for (int i = 0; i < orph.tagCount(); i++) {
            NBTTagCompound o = orph.getCompoundTagAt(i);
            r.orphans.put(o.getInteger("Slot"), TerrainSnapshot.fromNbt(o));
        }
        r.firepitBuilt = tag.getBoolean("FB");
        if (tag.hasKey("FS")) r.firepitSnapshot = TerrainSnapshot.fromNbt(tag.getCompoundTag("FS"));
        r.firepitLit = !tag.hasKey("FL") || tag.getBoolean("FL"); // old saves: a built firepit was always lit
        r.emptySinceTick = tag.hasKey("EST") ? tag.getLong("EST") : -1L;
        return r;
    }
}
