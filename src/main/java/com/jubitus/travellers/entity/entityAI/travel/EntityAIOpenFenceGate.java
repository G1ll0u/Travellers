package com.jubitus.travellers.entity.entityAI.travel;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
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
    private static final int SCAN_RADIUS = 1;
    private static final int OPEN_TIME = 20;
    private static final double KEEP_OPEN_RADIUS = 1.0;
    private static final int SCAN_INTERVAL = 10; // 2x par seconde
    private static final double MIN_MOVE_SQ = 0.01 * 0.01;

    private final EntityTraveller entity;
    private final java.util.Map<BlockPos, Integer> openTimers = new java.util.HashMap<>();

    private double lastScanX;
    private double lastScanY;
    private double lastScanZ;

    public EntityAIOpenFenceGate(EntityTraveller entity) {
        this.entity = entity;
        this.setMutexBits(0);
        this.lastScanX = entity.posX;
        this.lastScanY = entity.posY;
        this.lastScanZ = entity.posZ;
    }

    @Override
    public boolean shouldExecute() {
        if (entity.world == null || entity.world.isRemote) return false;
        if (!entity.isEntityAlive()) return false;

        // Ne rien faire offscreen
        if (!entity.isInPlayerViewDistanceApprox()) return false;

        // Pas utile si immobile / sans navigation
        if (entity.getNavigator() == null || entity.getNavigator().noPath()) {
            return !openTimers.isEmpty(); // continuer seulement pour fermer proprement
        }

        // Throttle
        return (entity.ticksExisted % SCAN_INTERVAL) == (entity.getEntityId() & (SCAN_INTERVAL - 1));
    }

    @Override
    public boolean shouldContinueExecuting() {
        return !openTimers.isEmpty() || shouldExecute();
    }

    @Override
    public void updateTask() {
        // Toujours gérer les timers, même si on ne scanne pas
        tickOpenTimers();

        if (!entity.isInPlayerViewDistanceApprox()) return;
        if (entity.getNavigator() == null || entity.getNavigator().noPath()) return;

        double dxm = entity.posX - lastScanX;
        double dym = entity.posY - lastScanY;
        double dzm = entity.posZ - lastScanZ;
        double movedSq = dxm * dxm + dym * dym + dzm * dzm;

        // Pas de scan si quasi immobile
        if (movedSq < MIN_MOVE_SQ) return;

        lastScanX = entity.posX;
        lastScanY = entity.posY;
        lastScanZ = entity.posZ;

        BlockPos base = new BlockPos(MathHelper.floor(entity.posX), MathHelper.floor(entity.posY), MathHelper.floor(entity.posZ));
        EnumFacing[] order = EnumFacing.HORIZONTALS.clone();

        Vec3d look = entity.getLookVec();
        EnumFacing primary = EnumFacing.getFacingFromVector((float) look.x, 0, (float) look.z);
        for (int i = 0; i < order.length; i++) {
            if (order[i] == primary) {
                EnumFacing t = order[0];
                order[0] = order[i];
                order[i] = t;
                break;
            }
        }

        for (int dy : new int[]{0, 1, -1}) {
            int y = base.getY() + dy;

            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    BlockPos p = new BlockPos(base.getX() + dx, y, base.getZ() + dz);

                    if (!isBlockLoadedNoForce(p)) continue;

                    IBlockState st = entity.world.getBlockState(p);
                    if (st.getBlock() instanceof BlockFenceGate && !st.getValue(BlockFenceGate.OPEN)) {
                        if (!isRoughlyForward(p)) continue;
                        openGate(p, st);
                    }
                }
            }

            BlockPos layer = new BlockPos(base.getX(), y, base.getZ());
            for (EnumFacing f : order) {
                BlockPos p = layer.offset(f);

                if (!isBlockLoadedNoForce(p)) continue;

                IBlockState st = entity.world.getBlockState(p);
                if (st.getBlock() instanceof BlockFenceGate && !st.getValue(BlockFenceGate.OPEN)) {
                    openGate(p, st);
                }
            }
        }
    }

    private void tickOpenTimers() {
        java.util.Iterator<java.util.Map.Entry<BlockPos, Integer>> it = openTimers.entrySet().iterator();

        while (it.hasNext()) {
            java.util.Map.Entry<BlockPos, Integer> e = it.next();
            BlockPos pos = e.getKey();

            if (!isBlockLoadedNoForce(pos)) {
                // Ne pas charger un chunk juste pour fermer un portillon
                it.remove();
                continue;
            }

            IBlockState st = entity.world.getBlockState(pos);
            if (!(st.getBlock() instanceof BlockFenceGate)) {
                it.remove();
                continue;
            }

            if (isAnyLivingNearLoadedOnly(pos, KEEP_OPEN_RADIUS)) {
                e.setValue(OPEN_TIME);
                continue;
            }

            int t = e.getValue() - 1;
            if (t <= 0) {
                if (st.getValue(BlockFenceGate.OPEN)) {
                    entity.world.setBlockState(pos, st.withProperty(BlockFenceGate.OPEN, false), 3);
                    entity.world.playSound(null, pos,
                            net.minecraft.init.SoundEvents.BLOCK_FENCE_GATE_CLOSE,
                            net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 1.0F);
                }
                it.remove();
            } else {
                e.setValue(t);
            }
        }
    }

    private boolean isBlockLoadedNoForce(BlockPos pos) {
        return entity.world.isBlockLoaded(pos, false);
    }

    private boolean isRoughlyForward(BlockPos p) {
        double dx = (p.getX() + 0.5) - entity.posX;
        double dz = (p.getZ() + 0.5) - entity.posZ;
        Vec3d look = entity.getLookVec();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return true;
        double dot = (dx / len) * look.x + (dz / len) * look.z;
        return dot > 0.1;
    }

    private void openGate(BlockPos pos, IBlockState st) {
        entity.world.setBlockState(pos, st.withProperty(BlockFenceGate.OPEN, true), 3);
        entity.world.playSound(null, pos,
                net.minecraft.init.SoundEvents.BLOCK_FENCE_GATE_OPEN,
                net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 1.0F);
        openTimers.put(pos.toImmutable(), OPEN_TIME);
    }

    private boolean isAnyLivingNearLoadedOnly(BlockPos pos, double r) {
        if (!isBlockLoadedNoForce(pos)) return false;
        AxisAlignedBB aabb = new AxisAlignedBB(pos).grow(r, 0.5, r);
        return !entity.world.getEntitiesWithinAABB(EntityLivingBase.class, aabb, Entity::isEntityAlive).isEmpty();
    }
}