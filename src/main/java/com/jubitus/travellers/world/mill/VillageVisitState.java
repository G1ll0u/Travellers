package com.jubitus.travellers.world.mill;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * A unit's stay in a village, owned by the loaded entity or by its virtual record and moved (never
 * copied) between the two at virtualize/materialize, like the itinerary. The loaded visit task keeps
 * only its walking/gaze/talk state; everything that must survive going offscreen or a restart is here.
 */
public final class VillageVisitState {

    public static final String NBT_KEY = "VillageVisit";

    /** The settlement's stop (town hall position, as the trip holds it). */
    public BlockPos stop;
    /** Stay left; 0 or less = leaving (out of buildings, back to the horse, then gone). */
    public int remainingTicks;
    /** A group follower dispersed in its leader's village: no timer, it leaves when the leader does. */
    public boolean asFollower;

    /** Offscreen only: the spot the record stands at / walks to, and what it faces there. */
    @Nullable
    public BlockPos spot;
    @Nullable
    public BlockPos facing;
    public long nextStepTick;

    /** Where the horse is tied at the village edge, when there is one. */
    @Nullable
    public BlockPos hitchPos;
    /** The horse was left standing at its post (a live entity) when its rider went offscreen. */
    public boolean horseAtPost;
    /** Offscreen: world tick the record started waiting at the post for a horse still standing there. */
    public long waitingForHorseSince = -1;

    public VillageVisitState(BlockPos stop, int remainingTicks, boolean asFollower) {
        this.stop = stop;
        this.remainingTicks = remainingTicks;
        this.asFollower = asFollower;
    }

    public boolean isLeaving() {
        return remainingTicks <= 0;
    }

    public NBTTagCompound write() {
        NBTTagCompound t = new NBTTagCompound();
        t.setLong("Stop", stop.toLong());
        t.setInteger("Remaining", remainingTicks);
        t.setBoolean("Follower", asFollower);
        if (spot != null) t.setLong("Spot", spot.toLong());
        if (facing != null) t.setLong("Facing", facing.toLong());
        t.setLong("NextStep", nextStepTick);
        if (hitchPos != null) t.setLong("Hitch", hitchPos.toLong());
        t.setBoolean("HorseAtPost", horseAtPost);
        t.setLong("WaitHorse", waitingForHorseSince);
        return t;
    }

    @Nullable
    public static VillageVisitState read(@Nullable NBTTagCompound t) {
        if (t == null || !t.hasKey("Stop")) return null;
        VillageVisitState s = new VillageVisitState(BlockPos.fromLong(t.getLong("Stop")),
                t.getInteger("Remaining"), t.getBoolean("Follower"));
        s.spot = t.hasKey("Spot") ? BlockPos.fromLong(t.getLong("Spot")) : null;
        s.facing = t.hasKey("Facing") ? BlockPos.fromLong(t.getLong("Facing")) : null;
        s.nextStepTick = t.getLong("NextStep");
        s.hitchPos = t.hasKey("Hitch") ? BlockPos.fromLong(t.getLong("Hitch")) : null;
        s.horseAtPost = t.getBoolean("HorseAtPost");
        s.waitingForHorseSince = t.hasKey("WaitHorse") ? t.getLong("WaitHorse") : -1;
        return s;
    }
}
