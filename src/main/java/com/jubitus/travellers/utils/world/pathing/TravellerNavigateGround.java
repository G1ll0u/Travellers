package com.jubitus.travellers.utils.world.pathing;

import com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon.CampfireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class TravellerNavigateGround extends PathNavigateGround {
    public TravellerNavigateGround(EntityLiving entity, World worldIn) {
        super(entity, worldIn);
    }

    @Override
    protected PathFinder getPathFinder() {
        TravellerNodeProcessor proc = new TravellerNodeProcessor();
        proc.setCanEnterDoors(true);
        proc.setCanOpenDoors(true);
        proc.setCanSwim(true);
        this.nodeProcessor = proc;
        return new PathFinder(proc);
    }


}

