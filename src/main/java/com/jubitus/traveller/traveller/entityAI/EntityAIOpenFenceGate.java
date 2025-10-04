package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class EntityAIOpenFenceGate extends EntityAIBase {
    private static final int SCAN_RADIUS = 1;   // 1 = 3x3 around XZ; bump to 2 if you want more reach
    private static final int OPEN_TIME = 20;  // keep open at least 1s
    private static final double KEEP_OPEN_RADIUS = 1.0; // if any living is within this, keep open
    private final EntityTraveller entity;
    // gates we’ve opened recently -> ticks remaining before auto-close
    private final java.util.Map<BlockPos, Integer> openTimers = new java.util.HashMap<>();

    public EntityAIOpenFenceGate(EntityTraveller entity) {
        this.entity = entity;
        this.setMutexBits(0); // do not block movement/looking
    }

    @Override
    public boolean shouldExecute() {
        // Always allowed while alive & moving around; cheap scan happens in updateTask.
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return true; // run every tick; we’re stateless
    }

    @Override
    public void updateTask() {
        // 1) Open any closed gates near y, y+1, y-1 (handles “gate on top of stairs”)
        BlockPos base = new BlockPos(MathHelper.floor(entity.posX), MathHelper.floor(entity.posY), MathHelper.floor(entity.posZ));
        EnumFacing[] order = EnumFacing.HORIZONTALS.clone();

        // bias scan order toward where we’re going
        Vec3d look = entity.getLookVec();
        EnumFacing primary = EnumFacing.getFacingFromVector((float) look.x, 0, (float) look.z);
        for (int i = 0; i < order.length; i++)
            if (order[i] == primary) {
                EnumFacing t = order[0];
                order[0] = order[i];
                order[i] = t;
                break;
            }

        for (int dy : new int[]{0, 1, -1}) {
            int y = base.getY() + dy;
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos p = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
                    IBlockState st = entity.world.getBlockState(p);
                    if (st.getBlock() instanceof BlockFenceGate && !st.getValue(BlockFenceGate.OPEN)) {
                        // optional: only open if roughly forward (helps with diagonals)
                        if (!isRoughlyForward(p)) continue;
                        openGate(p, st);
                    }
                }
            }
            // also check the 4 immediate neighbors in facing order (nice for 1-block diagonals)
            BlockPos layer = new BlockPos(base.getX(), y, base.getZ());
            for (EnumFacing f : order) {
                BlockPos p = layer.offset(f);
                IBlockState st = entity.world.getBlockState(p);
                if (st.getBlock() instanceof BlockFenceGate && !st.getValue(BlockFenceGate.OPEN)) {
                    openGate(p, st);
                }
            }
        }

        // 2) Maintain/close gates we opened
        java.util.Iterator<java.util.Map.Entry<BlockPos, Integer>> it = openTimers.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<BlockPos, Integer> e = it.next();
            BlockPos pos = e.getKey();
            IBlockState st = entity.world.getBlockState(pos);
            if (!(st.getBlock() instanceof BlockFenceGate)) {
                it.remove();
                continue;
            }

            // keep open while someone is nearby; refresh timer
            if (isAnyLivingNear(pos, KEEP_OPEN_RADIUS)) {
                e.setValue(OPEN_TIME);
                continue;
            }

            int t = e.getValue() - 1;
            if (t <= 0) {
                if (st.getValue(BlockFenceGate.OPEN)) {
                    entity.world.setBlockState(pos, st.withProperty(BlockFenceGate.OPEN, false), 3);
                    entity.world.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_FENCE_GATE_CLOSE,
                            net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 1.0F);
                }
                it.remove();
            } else {
                e.setValue(t);
            }
        }
    }

    // --- helpers ---

    private boolean isRoughlyForward(BlockPos p) {
        double dx = (p.getX() + 0.5) - entity.posX;
        double dz = (p.getZ() + 0.5) - entity.posZ;
        Vec3d look = entity.getLookVec();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return true;
        double dot = (dx / len) * look.x + (dz / len) * look.z;
        return dot > 0.1; // > ~84° cone in front; tweak as desired
    }

    private void openGate(BlockPos pos, IBlockState st) {
        entity.world.setBlockState(pos, st.withProperty(BlockFenceGate.OPEN, true), 3);
        entity.world.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_FENCE_GATE_OPEN,
                net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 1.0F);
        openTimers.put(pos.toImmutable(), OPEN_TIME);
    }

    private boolean isAnyLivingNear(BlockPos pos, double r) {
        AxisAlignedBB aabb = new AxisAlignedBB(pos).grow(r, 0.5, r);
        return !entity.world.getEntitiesWithinAABB(EntityLivingBase.class, aabb, Entity::isEntityAlive).isEmpty();
    }
}