package com.jubitus.travellers.utils.world.camp;

import com.jubitus.travellers.utils.world.build.BuildStep;
import com.jubitus.travellers.utils.world.build.IncrementalStructureBuilder;
import com.jubitus.travellers.utils.world.camp.shelter.ShelterPlacer;
import com.jubitus.travellers.utils.world.camp.shelter.ShelterTemplate;
import com.jubitus.travellers.utils.world.camp.shelter.ShelterTemplateRegistry;
import com.jubitus.travellers.utils.world.camp.shelter.TerrainSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * Single place that knows how to turn a {@link CampOccupant}'s logical build progress into world
 * blocks and back, so neither the loaded AI ({@code EntityAICampStay}) nor the virtual/reconcile path
 * ({@code CampsiteManager}) duplicate the placement logic — both just call in here.
 *
 * {@link #ensurePlan} is pure (template lookup + geometry only, no world access) so a slot's plan size
 * is known — and virtual progress can advance — even while its chunks are unloaded. Only
 * {@link #ensureTerrainPrepared} (grading + terrain snapshot, run once) and the place/break calls
 * actually touch blocks; callers must only invoke those when the footprint's chunks are loaded.
 */
public final class CampBuildCoordinator {

    private CampBuildCoordinator() {
    }

    /**
     * Picks/locks the template and computes the build plan. Pure — safe to call with unloaded chunks.
     *
     * <p>Once {@code shelterTemplateName} is locked in, it is <b>never</b> silently replaced: if the
     * template it names has since been deleted, renamed, or its whole culture folder removed, this
     * returns {@code null} (frozen) instead of picking a different template for an occupant that may
     * already have blocks placed against the original one's geometry — swapping identity mid-build is
     * exactly the "two structures sharing one template" corruption this must never cause. Callers treat
     * a null plan as "can't progress right now"; {@link #advanceVirtual} and
     * {@code EntityAICampStay.tickTearingDown} both already know how to cleanly finish a stuck teardown
     * without one (full snapshot restore, same as a death/ruin).
     */
    @Nullable
    public static List<BuildStep> ensurePlan(CampOccupant occ, ShelterSlot slot, Random rand) {
        if (occ.cachedPlan != null) return occ.cachedPlan;

        ShelterTemplate tpl;
        if (occ.shelterTemplateName != null) {
            String cultureForLookup = occ.shelterTemplateCulture != null ? occ.shelterTemplateCulture : occ.cultureId;
            tpl = ShelterTemplateRegistry.find(cultureForLookup, occ.shelterTemplateName);
            if (tpl == null) return null; // locked-in template is gone - freeze, never repick
        } else {
            tpl = ShelterTemplateRegistry.pick(occ.cultureId, rand);
            if (tpl == null) return null;
            occ.shelterTemplateName = tpl.name;
            occ.shelterTemplateCulture = tpl.culture;
        }
        occ.cachedPlan = withFillSteps(occ, slot, ShelterPlacer.planOnly(slot, tpl));
        // buildProgress/physicalMark are persisted against whatever plan existed when they were last
        // written — if the template .nbt on disk was replaced/edited (same name, same culture,
        // different block count) while a camp was mid-build, they can now point past the end of the
        // freshly computed plan. Clamp defensively: every caller (syncPhysical/placeNext/breakNext)
        // indexes this plan directly with no bounds check of its own.
        int size = occ.cachedPlan.size();
        if (occ.buildProgress > size) occ.buildProgress = size;
        if (occ.physicalMark > size) occ.physicalMark = size;
        return occ.cachedPlan;
    }

    /**
     * Grades + snapshots the terrain once (world-touching; call only when the footprint is loaded). No-op
     * once done — but still (re)populates {@code cachedPlan} via {@link #ensurePlan} if it's missing (e.g.
     * {@code snapshot} survived a restart via NBT but the transient plan cache didn't).
     *
     * <p>Before the first-ever capture for this occupant, flushes any stale physical leftovers on
     * {@code rec} — a previous occupant's deferred teardown ({@code pendingTeardowns}) or this slot's
     * abandoned ruin ({@code orphans}) — so the snapshot this captures is always bare terrain, never a
     * not-yet-restored structure. Without this, a slot reused right after a death or an unloaded
     * departure could bake the old shelter into the new occupant's snapshot, making it "restore" as a
     * ruin instead of the natural ground.
     *
     * <p>Also the last line of defense against flattening someone's build: right before the first
     * grading touch, the whole volume the shelter would occupy is re-validated as natural terrain —
     * a player (or another mod) may have built on the slot any time after it was computed, possibly
     * while the occupant only existed virtually. On failure the slot is permanently disabled and the
     * occupant evicted (returns false); no block was touched, and both the loaded AI and the virtual
     * record already self-heal from an occupant disappearing.
     *
     * @return true if the terrain is prepared (or already was); false if nothing can/may be built here.
     */
    public static boolean ensureTerrainPrepared(World world, CampsiteRecord rec, CampOccupant occ, ShelterSlot slot, Random rand) {
        if (occ.snapshot != null) {
            ensurePlan(occ, slot, rand);
            return true;
        }
        List<BuildStep> plan = ensurePlan(occ, slot, rand);
        if (plan == null) return false;
        String cultureForLookup = occ.shelterTemplateCulture != null ? occ.shelterTemplateCulture : occ.cultureId;
        ShelterTemplate tpl = ShelterTemplateRegistry.find(cultureForLookup, occ.shelterTemplateName);
        if (tpl == null) return false;

        // Restore our own leftovers (ruins/deferred teardowns) BEFORE validating, so a previous
        // occupant's half-torn shelter isn't mistaken for a player build.
        flushStaleTerrain(world, rec, occ.slotIndex);

        BlockPos[] vol = ShelterPlacer.buildVolume(slot, tpl);
        // Two independent guards: block-identity (isVolumeNatural, catches literal Millénaire blocks
        // and player builds) and reserved-zone metadata (catches a village building placed there out
        // of plain vanilla blocks, which the block-identity check alone would miss). Either one veto's.
        boolean villageZoneHit = world instanceof net.minecraft.world.WorldServer
                && com.jubitus.travellers.utils.world.mill.MillVillageIndex.anyBuildingZoneOverlapsPlanar(
                        world, vol[0], vol[1], com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.campsiteMinVillageDistance);
        if (villageZoneHit || !CampTerrainValidator.isVolumeNatural(world, vol[0], vol[1])) {
            slot.disabled = true;
            rec.occupants.remove(occ.slotIndex);
            if (world instanceof net.minecraft.world.WorldServer) {
                CampsiteManager mgr = CampsiteManager.get((net.minecraft.world.WorldServer) world);
                mgr.deindexOccupant(occ.travellerUUID);
                mgr.markDirty();
            }
            return false;
        }

        ShelterPlacer.BuildStart start = ShelterPlacer.startBuild(world, slot, tpl);
        occ.snapshot = start.snapshot;
        occ.fillCells = packCells(start.fillCells);
        occ.fillSurfaceStateId = net.minecraft.block.Block.getStateId(start.fillSurface);
        occ.fillSubStateId = net.minecraft.block.Block.getStateId(start.fillSub);
        // Fill steps are prepended to the plan, shifting every template step's index. Any progress
        // made virtually before this first terrain touch counted template steps only — bump it by the
        // fill count so it still points at the same template block (the platform is considered part of
        // that virtual head start; bulk syncPhysical will place it). Happens exactly once: fillCells
        // and snapshot are set together and this whole branch is snapshot==null-only.
        if (occ.buildProgress > 0) occ.buildProgress += start.fillCells.size();
        occ.cachedPlan = withFillSteps(occ, slot, start.plan);
        int planSize = occ.cachedPlan.size();
        if (occ.buildProgress > planSize) occ.buildProgress = planSize;
        return true;
    }

    /**
     * Prepends {@code occ}'s persisted ground-fill steps (terrain-matched banking, bottom-up) to the
     * template steps. Pure — fill cells/materials are persisted, never recomputed from the world, so
     * plan size and step indices are stable across restarts and virtualization. No-op for occupants
     * with no fill recorded (never touched terrain yet, or pre-fill saves).
     */
    private static List<BuildStep> withFillSteps(CampOccupant occ, ShelterSlot slot, List<BuildStep> templateSteps) {
        if (occ.fillCells == null || occ.fillCells.length == 0) return templateSteps;
        int floorY = slot.origin.getY();
        net.minecraft.block.state.IBlockState surface = net.minecraft.block.Block.getStateById(occ.fillSurfaceStateId);
        net.minecraft.block.state.IBlockState sub = net.minecraft.block.Block.getStateById(occ.fillSubStateId);
        List<BuildStep> full = new java.util.ArrayList<>(occ.fillCells.length / 3 + templateSteps.size());
        for (int i = 0; i + 2 < occ.fillCells.length; i += 3) {
            BlockPos p = new BlockPos(occ.fillCells[i], occ.fillCells[i + 1], occ.fillCells[i + 2]);
            full.add(new BuildStep(p, p.getY() >= floorY ? surface : sub, null,
                    net.minecraft.util.Mirror.NONE, net.minecraft.util.Rotation.NONE));
        }
        full.addAll(templateSteps);
        return full;
    }

    private static int[] packCells(List<BlockPos> cells) {
        int[] packed = new int[cells.size() * 3];
        int i = 0;
        for (BlockPos p : cells) {
            packed[i++] = p.getX();
            packed[i++] = p.getY();
            packed[i++] = p.getZ();
        }
        return packed;
    }

    /** Restores any not-yet-reconciled leftovers on {@code rec} before a fresh terrain snapshot is captured. */
    private static void flushStaleTerrain(World world, CampsiteRecord rec, int slotIndex) {
        if (!rec.pendingTeardowns.isEmpty()) {
            for (TerrainSnapshot s : rec.pendingTeardowns) s.restore(world);
            rec.pendingTeardowns.clear();
        }
        TerrainSnapshot ruin = rec.orphans.remove(slotIndex);
        if (ruin != null) ruin.restore(world);
    }

    /** Places the next unbuilt step (BUILDING). Returns it (for the caller to animate/react to), or null if already complete. */
    @Nullable
    public static BuildStep placeNext(World world, CampsiteRecord rec, CampOccupant occ, ShelterSlot slot, Random rand, long now) {
        // Catch up any progress made virtually first — stepping past a physical gap would set
        // physicalMark == buildProgress and permanently mask the missing blocks from reconcile.
        syncPhysical(world, rec, occ, slot, rand);
        if (rec.occupants.get(occ.slotIndex) != occ) return null; // evicted by the natural-terrain check
        List<BuildStep> plan = occ.cachedPlan;
        if (plan == null || occ.buildProgress >= plan.size()) return null;
        BuildStep step = plan.get(occ.buildProgress);
        IncrementalStructureBuilder.placeStep(world, step);
        occ.buildProgress++;
        occ.physicalMark = occ.buildProgress;
        occ.lastProgressTick = now;
        return step;
    }

    /** Breaks the topmost remaining step (TEARING_DOWN). Returns it, or null if already fully torn down. */
    @Nullable
    public static BuildStep breakNext(World world, CampsiteRecord rec, CampOccupant occ, ShelterSlot slot, Random rand, long now) {
        // Same catch-up rule as placeNext (see there).
        syncPhysical(world, rec, occ, slot, rand);
        if (rec.occupants.get(occ.slotIndex) != occ) return null; // evicted by the natural-terrain check
        List<BuildStep> plan = occ.cachedPlan;
        if (plan == null || occ.buildProgress <= 0) return null;
        BuildStep step = plan.get(occ.buildProgress - 1);
        IncrementalStructureBuilder.breakStep(world, step);
        occ.buildProgress--;
        occ.physicalMark = occ.buildProgress;
        occ.lastProgressTick = now;
        return step;
    }

    /**
     * Brings the physical world in line with {@code occ.buildProgress} in one shot (idempotent bulk
     * catch-up): used when progress advanced while offscreen/unloaded, or after a restart where
     * {@code physicalMark} is unknown. No-ops (cheap) once already in sync. Requires loaded chunks.
     */
    public static void syncPhysical(World world, CampsiteRecord rec, CampOccupant occ, ShelterSlot slot, Random rand) {
        if (!ensureTerrainPrepared(world, rec, occ, slot, rand)) return;
        List<BuildStep> plan = occ.cachedPlan;
        if (plan == null) return;
        // Re-clamp right here, not just in ensurePlan: this is the last line of defense against an
        // IndexOutOfBoundsException below if buildProgress/physicalMark ever end up stale relative to
        // cachedPlan (e.g. a save written by a build predating this clamp). Cheap; a no-op when in sync.
        int size = plan.size();
        if (occ.buildProgress > size) occ.buildProgress = size;
        if (occ.buildProgress < 0) occ.buildProgress = 0;
        if (occ.physicalMark > size) occ.physicalMark = size;
        if (occ.physicalMark == occ.buildProgress) return;

        if (occ.buildState == CampOccupant.BuildState.TEARING_DOWN) {
            int from = occ.physicalMark < 0 ? plan.size() : occ.physicalMark;
            for (int i = from - 1; i >= occ.buildProgress; i--) {
                IncrementalStructureBuilder.breakStep(world, plan.get(i));
            }
        } else {
            int from = Math.max(occ.physicalMark, 0);
            for (int i = from; i < occ.buildProgress; i++) {
                IncrementalStructureBuilder.placeStep(world, plan.get(i));
            }
        }
        occ.physicalMark = occ.buildProgress;
    }

    /**
     * Advances {@code occ.buildProgress} toward the plan's bounds based on elapsed world time since
     * its last progress change, at {@code ticksPerBlock} pace. Pure bookkeeping — no world access, so
     * it's safe (and required) to run for occupants whose chunks aren't loaded. Returns the new state:
     * true if the phase (build complete / teardown complete) just finished.
     */
    public static boolean advanceVirtual(CampOccupant occ, ShelterSlot slot, Random rand, long now, int ticksPerBlock) {
        List<BuildStep> plan = ensurePlan(occ, slot, rand);
        if (plan == null) {
            // Template unresolvable (deleted/renamed) with nothing to progress: a BUILDING occupant just
            // stays frozen at its current progress until departTick forces TEARING_DOWN regardless (see
            // CampsiteManager.processDepartures - it never waits on buildState). Once TEARING_DOWN, there
            // is no safe way to compute which blocks to break, so finish instantly instead of stalling
            // forever - CampsiteManager.finalizeDeparture restores the full terrain snapshot either way,
            // same mechanism as a death/ruin. Mirrors EntityAICampStay.tickTearingDown's loaded fallback.
            if (occ.buildState == CampOccupant.BuildState.TEARING_DOWN) {
                occ.buildProgress = 0;
                occ.physicalMark = 0;
                return true;
            }
            return false;
        }
        int steps = (int) ((now - occ.lastProgressTick) / Math.max(1, ticksPerBlock));
        if (steps <= 0) return false;
        occ.lastProgressTick += (long) steps * ticksPerBlock;

        if (occ.buildState == CampOccupant.BuildState.TEARING_DOWN) {
            occ.buildProgress = Math.max(0, occ.buildProgress - steps);
            return occ.buildProgress == 0;
        } else {
            occ.buildProgress = Math.min(plan.size(), occ.buildProgress + steps);
            return occ.buildProgress >= plan.size();
        }
    }
}
