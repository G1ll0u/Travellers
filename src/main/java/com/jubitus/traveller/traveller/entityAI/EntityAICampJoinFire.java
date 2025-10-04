package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.traveller.utils.blocks.CampfireBlock;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

public class EntityAICampJoinFire extends EntityAIBase {
    private final EntityTraveller mob;
    private final Random rand;

    private BlockPos firePos;
    private BlockPos seatPos;
    private int lingerTicks;

    public EntityAICampJoinFire(EntityTraveller mob) {
        this.mob = mob;
        this.rand = mob.getRNG();
        setMutexBits(1 | 2); // MOVE + LOOK while joining/idling
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false;
        if (mob.isFollowingSomeone()) return false;
        if (mob.getTargetVillage() == null) return false; // only during trips

        // small chance per tick to consider joining if we can see a nearby lit fire
        if (rand.nextInt(50) != 0) return false;

        BlockPos fire = findNearestLitFire(12);
        if (fire == null) return false;

        BlockPos seat = pickSeatAround(fire);
        if (seat == null) return false;

        this.firePos = fire;
        this.seatPos = seat;
        this.lingerTicks = 8 * 20 + rand.nextInt(10 * 20); // ~8–18s
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false;
        if (firePos == null) return false;

        IBlockState s = mob.world.getBlockState(firePos);
        if (!CampfireBlock.isLit(s)) return false;

        return lingerTicks > 0;
    }

    @Override
    public void startExecuting() {
        // walk to seat
        mob.getNavigator().tryMoveToXYZ(seatPos.getX() + 0.5, seatPos.getY(), seatPos.getZ() + 0.5, 0.35D);
    }

    @Override
    public void resetTask() {
        firePos = null;
        seatPos = null;
        mob.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        double d2 = mob.getDistanceSq(seatPos);
        if (d2 <= 2.0) {
            // sit/idle: stop pathing and look at fire or a nearby traveller
            mob.getNavigator().clearPath();

            if (rand.nextInt(5) == 0) {
                // glance at fire
                mob.getLookHelper().setLookPosition(firePos.getX() + 0.5, firePos.getY() + 0.7, firePos.getZ() + 0.5, 20.0F, 20.0F);
            } else {
                // glance at another traveller
                List<EntityTraveller> peers = mob.world.getEntitiesWithinAABB(EntityTraveller.class,
                        mob.getEntityBoundingBox().grow(6.0, 2.0, 6.0),
                        t -> t != mob && t.isEntityAlive());
                if (!peers.isEmpty()) {
                    EntityTraveller other = peers.get(rand.nextInt(peers.size()));
                    mob.getLookHelper().setLookPositionWithEntity(other, 30.0F, 30.0F);
                }
            }
        }
        lingerTicks--;
    }

    private BlockPos findNearestLitFire(int radius) {
        BlockPos base = mob.getPosition();
        int r = radius;
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                BlockPos p = base.add(dx, 0, dz);
                BlockPos col = new BlockPos(p.getX(), mob.world.getHeight(p).getY(), p.getZ());
                IBlockState s = mob.world.getBlockState(col);
                if (CampfireBlock.isLit(s)) {
                    double d2 = base.distanceSq(col);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = col;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos pickSeatAround(BlockPos fire) {
        // simple ring seats 2.5–3.5 blocks out, angle derived from UUID to spread people
        double base = (mob.getUniqueID().getMostSignificantBits() ^ mob.getUniqueID().getLeastSignificantBits()) & 0xFFFF;
        double angle = (base % 360) + rand.nextDouble() * 30 - 15; // ±15° jitter
        double dist = 2.6 + rand.nextDouble() * 0.9;

        for (int tries = 0; tries < 10; tries++) {
            double rad = Math.toRadians(angle + tries * 17); // spin a bit if blocked
            int x = fire.getX() + (int) Math.round(Math.cos(rad) * dist);
            int z = fire.getZ() + (int) Math.round(Math.sin(rad) * dist);
            int y = mob.world.getHeight(new BlockPos(x, 0, z)).getY();
            BlockPos p = new BlockPos(x, y, z);

            if (isStandable(p)) return p;
        }
        return null;
    }

    private boolean isStandable(BlockPos feet) {
        return mob.world.getBlockState(feet.down()).isSideSolid(mob.world, feet.down(), EnumFacing.UP)
                && mob.world.isAirBlock(feet)
                && mob.world.isAirBlock(feet.up());
    }
}
