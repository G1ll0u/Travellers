package com.jubitus.travellers.world.camp.shelter;

import com.jubitus.travellers.ground.BlockRules;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of a cuboid of blocks (states + tile-entity data) taken before a shelter is placed,
 * so the exact terrain can be restored when the shelter is torn down. Persisted so restoration
 * works even when triggered by reconciliation rather than the original builder.
 */
public class TerrainSnapshot {

    private BlockPos origin;
    private int sizeX, sizeY, sizeZ;
    private int[] stateIds;
    private final Map<Integer, NBTTagCompound> tileData = new HashMap<>();

    private TerrainSnapshot() {
    }

    /** World-space cuboid this snapshot covers - the shelter's own footprint, used to keep other
     * placement (e.g. a materializing mount) out of it. */
    public AxisAlignedBB getBounds() {
        return new AxisAlignedBB(origin, origin.add(sizeX - 1, sizeY - 1, sizeZ - 1));
    }

    public static TerrainSnapshot capture(World world, BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        TerrainSnapshot s = new TerrainSnapshot();
        s.origin = origin;
        s.sizeX = sizeX;
        s.sizeY = sizeY;
        s.sizeZ = sizeZ;
        s.stateIds = new int[sizeX * sizeY * sizeZ];
        int i = 0;
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++, i++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    IBlockState bs = world.getBlockState(p);
                    if (BlockRules.isTreePart(bs) || BlockRules.isVegetation(bs)) {
                        s.stateIds[i] = 0; // restore to air — don't re-plant trees or foliage on teardown
                    } else {
                        s.stateIds[i] = Block.getStateId(bs);
                        TileEntity te = world.getTileEntity(p);
                        if (te != null) s.tileData.put(i, te.writeToNBT(new NBTTagCompound()));
                    }
                }
            }
        }
        return s;
    }

    public void restore(World world) {
        if (stateIds == null) return;
        IBlockState air = Blocks.AIR.getDefaultState();
        // Pass 1: clear the cuboid to air WITHOUT neighbour notify (flag 2), removing tile entities
        // first. This deletes the shelter's beds/doors/chests/torches/firepit cleanly with no drops
        // and no break-cascade.
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (world.getTileEntity(p) != null) world.removeTileEntity(p);
                    world.setBlockState(p, air, 2);
                }
            }
        }
        // Pass 2: restore original terrain WITH notify (flag 3). No shelter blocks remain to drop,
        // and flag 3 propagates light + render updates to clients correctly (removed light sources
        // no longer leave stale lighting).
        int i = 0;
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++, i++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    world.setBlockState(p, Block.getStateById(stateIds[i]), 3);
                    NBTTagCompound teNbt = tileData.get(i);
                    if (teNbt != null) {
                        TileEntity te = world.getTileEntity(p);
                        if (te != null) {
                            teNbt.setInteger("x", p.getX());
                            teNbt.setInteger("y", p.getY());
                            teNbt.setInteger("z", p.getZ());
                            te.readFromNBT(teNbt);
                        }
                    }
                }
            }
        }
        // Guarantee correct lighting (incl. light removed sources spilled outside the cuboid).
        if (world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
            CampLighting.refresh(ws, origin, sizeX, sizeY, sizeZ);
            // Restoring original terrain can raise solid ground back up under anyone standing in the
            // footprint (e.g. the departing occupant finishing its walk) — pop them back onto solid ground.
            com.jubitus.travellers.virtual.MaterializePositionResolver.unstickEntitiesInVolume(
                    ws, origin, origin.add(sizeX - 1, sizeY - 1, sizeZ - 1));
        }
    }

    /**
     * Same as {@link #restore} but never touches a column (x,z) that falls inside a Millénaire
     * building's reserved zone (real footprint metadata via
     * {@link com.jubitus.travellers.world.mill.MillFootprints#reservedIn} —
     * not block identity, since Millénaire buildings are frequently made of plain vanilla blocks),
     * nor a cell that already holds a literal Millénaire block (belt-and-braces for decorative
     * blocks placed slightly outside the computed zone box). Used when a camp is force-evacuated
     * because a village/lone-building grew into ground it occupies: our own shelter must come down,
     * but the village's own territory — whatever it's physically built from — must never be cleared
     * or overwritten.
     */
    public void restoreSkippingVillageZone(net.minecraft.world.WorldServer world) {
        if (stateIds == null) return;
        List<com.jubitus.travellers.world.mill.MillFootprints.Footprint> zones =
                com.jubitus.travellers.world.mill.MillFootprints.reservedIn(world, origin, origin.add(sizeX - 1, 0, sizeZ - 1));
        IBlockState air = Blocks.AIR.getDefaultState();
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (isProtected(world, zones, p)) continue;
                    if (world.getTileEntity(p) != null) world.removeTileEntity(p);
                    world.setBlockState(p, air, 2);
                }
            }
        }
        int i = 0;
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++, i++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (isProtected(world, zones, p)) continue;
                    world.setBlockState(p, Block.getStateById(stateIds[i]), 3);
                    NBTTagCompound teNbt = tileData.get(i);
                    if (teNbt != null) {
                        TileEntity te = world.getTileEntity(p);
                        if (te != null) {
                            teNbt.setInteger("x", p.getX());
                            teNbt.setInteger("y", p.getY());
                            teNbt.setInteger("z", p.getZ());
                            te.readFromNBT(teNbt);
                        }
                    }
                }
            }
        }
        CampLighting.refresh(world, origin, sizeX, sizeY, sizeZ);
        com.jubitus.travellers.virtual.MaterializePositionResolver.unstickEntitiesInVolume(
                world, origin, origin.add(sizeX - 1, sizeY - 1, sizeZ - 1));
    }

    private static boolean isProtected(World world, List<com.jubitus.travellers.world.mill.MillFootprints.Footprint> zones, BlockPos p) {
        if (com.jubitus.travellers.world.mill.MillFootprints.anyReservedContains(zones, p.getX(), p.getZ())) {
            return true;
        }
        return com.jubitus.travellers.ground.BlockRules.isMillenaireBlock(world.getBlockState(p));
    }

    /**
     * Cells whose current world state differs from the snapshot, in the order a hand-worked restore
     * should process them: cells restoring to air first, top-down (clearing graded fill/leftovers),
     * then solid cells bottom-up (rebuilding the original terrain support-first). Pairs with
     * {@link #restoreCell}: an entity works through this list block by block instead of the bulk
     * {@link #restore}. Recomputing after an interruption naturally resumes where it left off.
     */
    public List<BlockPos> computeRestoreOrder(World world) {
        List<BlockPos> toAir = new ArrayList<>();
        List<BlockPos> toSolid = new ArrayList<>();
        if (stateIds == null) return toAir;
        int i = 0;
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++, i++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    IBlockState target = Block.getStateById(stateIds[i]);
                    if (world.getBlockState(p) == target) continue;
                    (target.getBlock() == Blocks.AIR ? toAir : toSolid).add(p);
                }
            }
        }
        toAir.sort(Comparator.comparingInt(BlockPos::getY).reversed());
        toSolid.sort(Comparator.comparingInt(BlockPos::getY));
        toAir.addAll(toSolid);
        return toAir;
    }

    /**
     * Restores a single cell of this snapshot (state + tile-entity data) with the visible effects of
     * an entity working it: break particles for whatever it replaces, the target block's place sound
     * when something is (re)placed. Idempotent; no-op for positions outside the snapshot.
     */
    public void restoreCell(World world, BlockPos pos) {
        if (stateIds == null) return;
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) return;
        int i = dx + sizeX * (dz + sizeZ * dy);
        IBlockState target = Block.getStateById(stateIds[i]);
        IBlockState cur = world.getBlockState(pos);
        if (cur == target) return;

        if (!cur.getBlock().isAir(cur, world, pos)) {
            world.playEvent(2001, pos, Block.getStateId(cur));
        }
        if (world.getTileEntity(pos) != null) world.removeTileEntity(pos);
        world.setBlockState(pos, target, 3);
        NBTTagCompound teNbt = tileData.get(i);
        if (teNbt != null) {
            TileEntity te = world.getTileEntity(pos);
            if (te != null) {
                NBTTagCompound copy = teNbt.copy();
                copy.setInteger("x", pos.getX());
                copy.setInteger("y", pos.getY());
                copy.setInteger("z", pos.getZ());
                te.readFromNBT(copy);
            }
        }
        if (target.getBlock() != Blocks.AIR) {
            net.minecraft.block.SoundType sound = target.getBlock().getSoundType();
            world.playSound(null, pos, sound.getPlaceSound(), net.minecraft.util.SoundCategory.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        }
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("OX", origin.getX());
        tag.setInteger("OY", origin.getY());
        tag.setInteger("OZ", origin.getZ());
        tag.setInteger("SX", sizeX);
        tag.setInteger("SY", sizeY);
        tag.setInteger("SZ", sizeZ);
        tag.setIntArray("States", stateIds);
        NBTTagList tes = new NBTTagList();
        for (Map.Entry<Integer, NBTTagCompound> e : tileData.entrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("I", e.getKey());
            t.setTag("D", e.getValue());
            tes.appendTag(t);
        }
        tag.setTag("Tiles", tes);
        return tag;
    }

    public static TerrainSnapshot fromNbt(NBTTagCompound tag) {
        TerrainSnapshot s = new TerrainSnapshot();
        s.origin = new BlockPos(tag.getInteger("OX"), tag.getInteger("OY"), tag.getInteger("OZ"));
        s.sizeX = tag.getInteger("SX");
        s.sizeY = tag.getInteger("SY");
        s.sizeZ = tag.getInteger("SZ");
        s.stateIds = tag.getIntArray("States");
        NBTTagList tes = tag.getTagList("Tiles", 10);
        for (int i = 0; i < tes.tagCount(); i++) {
            NBTTagCompound t = tes.getCompoundTagAt(i);
            s.tileData.put(t.getInteger("I"), t.getCompoundTag("D"));
        }
        return s;
    }
}
