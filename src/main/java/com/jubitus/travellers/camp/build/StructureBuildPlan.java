package com.jubitus.travellers.camp.build;

import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an ordered, bottom-to-top list of {@link BuildStep}s from a vanilla {@link Template},
 * for placing (or, reversed, tearing down) a structure one block at a time instead of all at once.
 *
 * {@link Template#writeToNBT} exposes the exact same "palette"/"blocks" NBT format vanilla uses to
 * save structures, in Mojang's own capture order: solid full-cube blocks, then tile-entity blocks,
 * then everything else (doors, beds, torches, stairs...). Rather than inventing our own rule for what
 * counts as a "detail" block, we replicate that classification (it's a public, stable, unobfuscated
 * NBT format — no reflection into Template's private fields needed) and then sort each tier by final
 * world Y so the result is strictly bottom-up regardless of the file's raw scan order.
 */
public final class StructureBuildPlan {

    private StructureBuildPlan() {
    }

    private enum Tier {STRUCTURAL, TILE_ENTITY, SPECIAL}

    /**
     * @param template the structure to build
     * @param settings rotation/mirror to apply (as used for placement)
     * @param anchor   world position the template's local (0,0,0) maps to after transform
     */
    public static List<BuildStep> build(Template template, PlacementSettings settings, BlockPos anchor) {
        NBTTagCompound raw = template.writeToNBT(new NBTTagCompound());

        List<IBlockState> palette = new ArrayList<>();
        NBTTagList paletteTag = raw.getTagList("palette", 10);
        for (int i = 0; i < paletteTag.tagCount(); i++) {
            palette.add(NBTUtil.readBlockState(paletteTag.getCompoundTagAt(i)));
        }

        List<Ranked> ranked = new ArrayList<>();
        NBTTagList blocksTag = raw.getTagList("blocks", 10);
        for (int i = 0; i < blocksTag.tagCount(); i++) {
            NBTTagCompound entry = blocksTag.getCompoundTagAt(i);
            NBTTagList posTag = entry.getTagList("pos", 3);
            BlockPos localPos = new BlockPos(posTag.getIntAt(0), posTag.getIntAt(1), posTag.getIntAt(2));
            IBlockState localState = palette.get(entry.getInteger("state"));
            NBTTagCompound tileNbt = entry.hasKey("nbt") ? entry.getCompoundTag("nbt") : null;

            // Template capture (toIgnore=null) records every position in the bounding box, including
            // literal air — it's how vanilla structures clear pre-existing terrain on placement. Our
            // shelters don't need that: ShelterPlacer.gradeFootprint already clears the whole footprint
            // volume up-front before the incremental build starts, so an air step here would just be an
            // invisible no-op block placement (a wasted tick + swing + build-progress slot).
            if (localState.getBlock() == net.minecraft.init.Blocks.AIR) continue;

            Tier tier = tileNbt != null ? Tier.TILE_ENTITY
                    : (!localState.isFullBlock() && !localState.isFullCube()) ? Tier.SPECIAL
                    : Tier.STRUCTURAL;

            BlockPos worldPos = Template.transformedBlockPos(settings, localPos).add(anchor);
            IBlockState worldState = localState.withMirror(settings.getMirror()).withRotation(settings.getRotation());
            ranked.add(new Ranked(tier, new BuildStep(worldPos, worldState, tileNbt, settings.getMirror(), settings.getRotation())));
        }

        mergeTwoPartBlocks(ranked);

        ranked.sort(Comparator
                .<Ranked>comparingInt(r -> r.tier.ordinal())
                .thenComparingInt(r -> r.step.pos.getY())
                .thenComparingInt(r -> r.step.pos.getZ())
                .thenComparingInt(r -> r.step.pos.getX()));

        List<BuildStep> steps = new ArrayList<>(ranked.size());
        for (Ranked r : ranked) steps.add(r.step);
        return steps;
    }

    /**
     * Folds vanilla's two-block-per-fixture blocks (doors: upper+lower, beds: head+foot) into a single
     * {@link BuildStep} via {@link BuildStep#companion} and drops the companion's own list entry. See
     * {@link BuildStep} for why: placing/breaking either half alone, with unrelated steps in between,
     * leaves a window where the other half doesn't exist yet and the placed half self-destructs.
     */
    private static void mergeTwoPartBlocks(List<Ranked> ranked) {
        Map<BlockPos, Ranked> byPos = new HashMap<>();
        for (Ranked r : ranked) byPos.put(r.step.pos, r);

        List<Ranked> toRemove = new ArrayList<>();
        for (Ranked r : ranked) {
            IBlockState state = r.step.state;
            if (state.getBlock() instanceof BlockDoor && state.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.LOWER) {
                Ranked upper = byPos.get(r.step.pos.up());
                if (upper != null && upper.step.state.getBlock() == state.getBlock()) {
                    r.step.companion = upper.step;
                    toRemove.add(upper);
                }
            } else if (state.getBlock() instanceof BlockBed && state.getValue(BlockBed.PART) == BlockBed.EnumPartType.FOOT) {
                EnumFacing facing = state.getValue(BlockBed.FACING);
                Ranked head = byPos.get(r.step.pos.offset(facing));
                if (head != null && head.step.state.getBlock() == state.getBlock()) {
                    r.step.companion = head.step;
                    toRemove.add(head);
                }
            }
        }
        ranked.removeAll(toRemove);
    }

    private static final class Ranked {
        final Tier tier;
        final BuildStep step;

        Ranked(Tier tier, BuildStep step) {
            this.tier = tier;
            this.step = step;
        }
    }
}
