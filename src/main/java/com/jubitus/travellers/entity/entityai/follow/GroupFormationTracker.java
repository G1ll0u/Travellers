package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.entity.entityai.follow.formations.FormationGeometry;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lighter, loaded-side counterpart to {@link FollowFormationCoordinator} for plain traveller/raider
 * group followers - a real formation shape (see {@link WorkerFormationStyle}), without any of the
 * concerns that only apply to a player-owned worker escort: no cave/tunnel geometry (a group never
 * used it before this class existed), no escort slots, no GUI-configurable settings (a group's style
 * is rolled once at creation, see {@code TravellerSoftSpawner}), no persistent-ordinal stability
 * pass. Groups are small (militia/convoy, not a 40-member escort), so a full dense-rank recompute
 * every refresh is cheap enough that the stability machinery workers need has no equivalent here.
 */
public final class GroupFormationTracker {

    private static final long MEMBER_EXPIRY_TICKS = 40L;
    private static final long REFRESH_INTERVAL_TICKS = 20L;

    /** A leader that hasn't displaced at least this far (squared) since the last refresh is treated
     *  as stationary - the heading is held at its last real value rather than re-derived from a
     *  near-zero displacement vector, whose direction is noise (same trap the mounted-follow pacer
     *  documents for the identical reason). */
    private static final double HEADING_MOVE_EPSILON_SQ = 0.05D * 0.05D;

    private static final class LeaderContext {
        final Map<UUID, Long> registered = new HashMap<>();
        List<UUID> orderedIds = Collections.emptyList();
        double headingX = 1.0D;
        double headingZ = 0.0D;
        boolean hasHeading = false;
        long nextRefreshTick = 0L;
        long lastActiveTick = 0L;
    }

    private static void cleanupIfDue(long now) {
        if (now - lastCleanupTick < CLEANUP_INTERVAL_TICKS) return;
        lastCleanupTick = now;
        CONTEXTS.entrySet().removeIf(e -> now - e.getValue().lastActiveTick > CONTEXT_EXPIRY_TICKS);
    }

    // A leader whose context has had no active follower for this long is dropped outright - without
    // this, CONTEXTS grows by one entry per distinct group-leader UUID ever seen for the life of the
    // world (leaders die, groups dissolve, but nothing else here ever removes the map entry).
    private static final long CONTEXT_EXPIRY_TICKS = 6000L; // 5 minutes
    private static final long CLEANUP_INTERVAL_TICKS = 1200L; // 1 minute
    private static long lastCleanupTick = Long.MIN_VALUE;

    private static final Map<UUID, LeaderContext> CONTEXTS = new HashMap<>();

    private GroupFormationTracker() {
    }

    public static FollowFormationCoordinator.SlotAssignment getGroupSlot(EntityTraveller follower,
                                                                          EntityLivingBase leader,
                                                                          WorkerFormationStyle style,
                                                                          double baseFollowDistance,
                                                                          boolean mounted,
                                                                          long now) {
        cleanupIfDue(now);

        UUID leaderId = leader.getUniqueID();
        LeaderContext ctx = CONTEXTS.computeIfAbsent(leaderId, k -> new LeaderContext());
        ctx.registered.put(follower.getUniqueID(), now);
        ctx.lastActiveTick = now;

        if (now >= ctx.nextRefreshTick) {
            ctx.nextRefreshTick = now + REFRESH_INTERVAL_TICKS;
            ctx.registered.entrySet().removeIf(e -> now - e.getValue() > MEMBER_EXPIRY_TICKS);
            List<UUID> ids = new ArrayList<>(ctx.registered.keySet());
            Collections.sort(ids);
            ctx.orderedIds = ids;

            double dx = leader.posX - leader.prevPosX;
            double dz = leader.posZ - leader.prevPosZ;
            if (dx * dx + dz * dz > HEADING_MOVE_EPSILON_SQ) {
                double len = Math.sqrt(dx * dx + dz * dz);
                ctx.headingX = dx / len;
                ctx.headingZ = dz / len;
                ctx.hasHeading = true;
            } else if (!ctx.hasHeading) {
                ctx.headingX = 1.0D;
                ctx.headingZ = 0.0D;
            }
            // else: leader is effectively stationary this refresh - keep the last real heading.
        }

        double leaderDx = leader.posX - leader.prevPosX;
        double leaderDz = leader.posZ - leader.prevPosZ;
        double leaderSpeed = Math.sqrt(leaderDx * leaderDx + leaderDz * leaderDz);
        boolean leaderActuallyMoving = leaderSpeed > 0.005D;
        double bodyScale = mounted ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D;

        if (style == WorkerFormationStyle.RING) {
            FormationGeometry.SlotOffset off = FormationGeometry.computeRingOffset(follower.getUniqueID());
            return FollowFormationCoordinator.buildFixedSlot(Math.max(1, ctx.orderedIds.size()),
                    leaderSpeed, leaderActuallyMoving,
                    leader.posX + off.dx, leader.posY, leader.posZ + off.dz,
                    off, ctx.headingX, ctx.headingZ);
        }

        int ordinal = Math.max(0, ctx.orderedIds.indexOf(follower.getUniqueID()));
        int groupSize = Math.max(1, ctx.orderedIds.size());

        WorkerFormationSettings settings = WorkerFormationSettings.defaults();
        settings.style = style;
        settings.numEscorts = 0;

        FormationGeometry.SlotOffset off = FormationGeometry.computeSlotOffset(
                ordinal, groupSize, ctx.headingX, ctx.headingZ, baseFollowDistance, leaderId,
                settings, true, bodyScale);

        return FollowFormationCoordinator.buildFixedSlot(groupSize, leaderSpeed, leaderActuallyMoving,
                leader.posX + off.dx, leader.posY, leader.posZ + off.dz,
                off, ctx.headingX, ctx.headingZ);
    }
}
