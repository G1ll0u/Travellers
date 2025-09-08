package com.jubitus.traveller.traveller.pathing;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class TravellerNavigateGround extends PathNavigateGround {
    public TravellerNavigateGround(EntityLiving entity, World worldIn) {
        super(entity, worldIn);
    }

    @Override
    protected PathFinder getPathFinder() {
        TravellerNodeProcessor proc = new TravellerNodeProcessor();
        proc.setCanEnterDoors(true); // key: planner will go to/through “closed door” nodes
        proc.setCanOpenDoors(true);
        proc.setCanSwim(true);
        this.nodeProcessor = proc;
        return new PathFinder(proc);
    }
    // IIRC, it avoids them to stuck in diagonal placed unpassable blocks.
    @Override
    protected boolean isDirectPathBetweenPoints(Vec3d a, Vec3d b, int sx, int sy, int sz) {
        return false;
    }
}
