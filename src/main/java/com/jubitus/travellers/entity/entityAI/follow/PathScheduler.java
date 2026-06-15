package com.jubitus.travellers.entity.entityAI.follow;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class PathScheduler {

    public static int GLOBAL_MAX_PATHS_PER_TICK = 280;

    public static double OWNER_PATHS_PER_SECOND = 250.0;
    public static double OWNER_MAX_BURST = 32.0;

    public static int OWNER_STATE_EXPIRE_TICKS = 20 * 30;
    public static int FOLLOWER_STATE_EXPIRE_TICKS = 20 * 12;

    private static final Map<UUID, OwnerBucket> buckets = new HashMap<>();

    private static long globalTick = Long.MIN_VALUE;
    private static int globalUsed = 0;
    private static long lastCleanupTick = Long.MIN_VALUE;

    private PathScheduler() {
    }

    public static boolean tryAcquire(World world, @Nullable EntityLivingBase owner, int followerEntityId, int priority) {
        AcquireResult result = tryAcquireDetailed(world, owner, followerEntityId, priority, 1);
        return result == AcquireResult.GRANTED;
    }

    public static boolean tryAcquire(World world,
                                     @Nullable EntityLivingBase owner,
                                     int followerEntityId,
                                     int priority,
                                     int groupSize) {
        AcquireResult result = tryAcquireDetailed(world, owner, followerEntityId, priority, groupSize);
        return result == AcquireResult.GRANTED;
    }

    public static AcquireResult tryAcquireDetailed(World world,
                                                   @Nullable EntityLivingBase owner,
                                                   int followerEntityId,
                                                   int priority,
                                                   int groupSize) {
        if (owner == null) {
            return AcquireResult.DENIED_OWNER_MISSING;
        }

        long now = world.getTotalWorldTime();
        beginTick(now);
        cleanup(now);

        if (globalUsed >= GLOBAL_MAX_PATHS_PER_TICK) {
            return AcquireResult.DENIED_GLOBAL_LIMIT;
        }

        UUID ownerId = owner.getUniqueID();
        OwnerBucket bucket = buckets.get(ownerId);
        if (bucket == null) {
            bucket = new OwnerBucket();
            bucket.tick = now;
            bucket.tokens = OWNER_MAX_BURST;
            bucket.lastSeenTick = now;
            buckets.put(ownerId, bucket);
        }

        refill(bucket, now);
        bucket.lastSeenTick = now;

        FollowerState follower = bucket.followers.get(followerEntityId);
        if (follower == null) {
            follower = new FollowerState();
            follower.lastSeenTick = now;
            bucket.followers.put(followerEntityId, follower);
        }
        follower.lastSeenTick = now;

        double requestCost = computeBaseCost(priority, groupSize);

        double starvationDiscount = computeStarvationDiscount(follower, priority);
        double effectiveCost = Math.max(0.45D, requestCost - starvationDiscount);

        boolean mildSpreadDenied = shouldApplyMildSpread(now, followerEntityId, priority, follower);

        if (mildSpreadDenied) {
            onDenied(follower, now);
            return AcquireResult.DENIED_SPREAD;
        }

        double minRequired = 0.0D;
        if (priority <= 0) {
            minRequired = effectiveCost;
        } else if (priority == 1) {
            minRequired = Math.min(effectiveCost, 0.50D);
        } else {
            minRequired = -1.0D;
        }

        if (bucket.tokens < minRequired) {
            onDenied(follower, now);
            return AcquireResult.DENIED_OWNER_TOKENS;
        }

        bucket.tokens -= effectiveCost;
        globalUsed++;

        onGranted(follower, now);
        return AcquireResult.GRANTED;
    }

    public static boolean wasSchedulerDenial(AcquireResult result) {
        return result == AcquireResult.DENIED_GLOBAL_LIMIT
                || result == AcquireResult.DENIED_OWNER_TOKENS
                || result == AcquireResult.DENIED_SPREAD;
    }

    private static void beginTick(long now) {
        if (now != globalTick) {
            globalTick = now;
            globalUsed = 0;
        }
    }

    private static void refill(OwnerBucket bucket, long now) {
        long dt = now - bucket.tick;
        if (dt <= 0) {
            return;
        }

        double refillPerTick = OWNER_PATHS_PER_SECOND / 20.0D;
        bucket.tokens = Math.min(OWNER_MAX_BURST, bucket.tokens + dt * refillPerTick);
        bucket.tick = now;
    }

    private static double computeBaseCost(int priority, int groupSize) {
        double cost = 1.0D;

        int extraFollowers = Math.max(0, groupSize - 12);
        cost += extraFollowers * 0.022D;
        cost = Math.min(cost, 2.0D);

        if (priority >= 2) {
            cost *= 0.68D;
        } else if (priority == 1) {
            cost *= 0.86D;
        }

        return cost;
    }

    private static double computeStarvationDiscount(FollowerState follower, int priority) {
        double discount = 0.0D;

        discount += Math.min(0.70D, follower.denialStreak * 0.11D);
        discount += Math.min(0.55D, follower.waitCredit * 0.04D);

        if (priority >= 2) {
            discount += 0.28D;
        } else if (priority == 1) {
            discount += 0.14D;
        }

        return discount;
    }

    private static boolean shouldApplyMildSpread(long now,
                                                 int followerEntityId,
                                                 int priority,
                                                 FollowerState follower) {
        if (priority >= 2) {
            return false;
        }

        if (follower.denialStreak >= 2 || follower.waitCredit >= 4) {
            return false;
        }

        int lanes = 3;
        int lane = Math.floorMod(followerEntityId * 17, lanes);
        int tickLane = (int) Math.floorMod(now, lanes);

        return lane != tickLane;
    }

    private static void onDenied(FollowerState follower, long now) {
        follower.denialStreak = Math.min(8, follower.denialStreak + 1);
        follower.waitCredit = Math.min(12, follower.waitCredit + 1);
        follower.lastDeniedTick = now;
    }

    private static void onGranted(FollowerState follower, long now) {
        follower.denialStreak = 0;
        follower.waitCredit = Math.max(0, follower.waitCredit - 3);
        follower.lastGrantedTick = now;
    }

    private static void cleanup(long now) {
        // Rate-limited: with 200+ workers each calling tryAcquireDetailed every tick,
        // running the full nested-map iteration on every call costs O(workers × buckets × followers).
        // Once per 200 ticks is plenty for expiring stale state.
        if (lastCleanupTick != Long.MIN_VALUE && (now - lastCleanupTick) < 200) return;
        lastCleanupTick = now;

        Iterator<Map.Entry<UUID, OwnerBucket>> ownerIt = buckets.entrySet().iterator();
        while (ownerIt.hasNext()) {
            Map.Entry<UUID, OwnerBucket> entry = ownerIt.next();
            OwnerBucket bucket = entry.getValue();

            if ((now - bucket.lastSeenTick) > OWNER_STATE_EXPIRE_TICKS) {
                ownerIt.remove();
                continue;
            }

            Iterator<Map.Entry<Integer, FollowerState>> followerIt = bucket.followers.entrySet().iterator();
            while (followerIt.hasNext()) {
                Map.Entry<Integer, FollowerState> f = followerIt.next();
                if ((now - f.getValue().lastSeenTick) > FOLLOWER_STATE_EXPIRE_TICKS) {
                    followerIt.remove();
                }
            }
        }
    }

    public enum AcquireResult {
        GRANTED,
        DENIED_OWNER_MISSING,
        DENIED_GLOBAL_LIMIT,
        DENIED_OWNER_TOKENS,
        DENIED_SPREAD
    }

    private static final class OwnerBucket {
        long tick;
        long lastSeenTick;
        double tokens;
        final Map<Integer, FollowerState> followers = new HashMap<>();
    }

    private static final class FollowerState {
        long lastSeenTick;
        long lastDeniedTick;
        long lastGrantedTick;
        int denialStreak;
        int waitCredit;
    }
}