package com.jubitus.traveller.traveller.camp;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class TravellerCampBehavior {

    private final EntityTraveller owner;
    private final Map<BlockPos, Integer> campAvoidUntil = new HashMap<>();
    // moved fields from EntityTraveller
    @Nullable
    private final BlockPos campfirePos = null;
    private final boolean campLeader = false;
    private final int campJoinCooldown = 0;
    public long campLeaveTime = 0L;
    // social fields etc…

    public TravellerCampBehavior(EntityTraveller owner) {
        this.owner = owner;
    }
}
