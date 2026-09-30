package com.jubitus.travellers.debug.trace;

import com.jubitus.travellers.entity.entityai.follow.EntityAIFollowGroupLeader;
import com.jubitus.travellers.entity.entityai.follow.EntityAIFollowLeader;
import com.jubitus.travellers.entity.entityai.travel.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;

import java.util.EnumSet;
import java.util.Locale;

/**
 * One tick's worth of state for one traveller, held as primitives.
 *
 * <p>Primitives rather than a formatted string on purpose: every loaded traveller feeds a small
 * rolling buffer of these so an automatic capture can include the seconds <em>before</em> the
 * incident, and formatting them all every few ticks would be pure waste for the ones nothing ever
 * happens to. {@link #format} is the single place that turns one into text, shared by the live path
 * and the replayed buffer so the two can never drift.
 */
public final class TraceSample {

    public int tick;

    // MOVE
    public double x, y, z;
    public float yaw;
    public float headYaw, bodyYaw;
    public String headBy = "-";
    public String look = "-";
    public double dxz;
    public boolean onGround;
    public boolean collided;

    // PATH
    public boolean hasPath;
    public int pathIdx, pathLen;
    public int nodeX, nodeY, nodeZ;
    public int destX, destY, destZ;

    // STEER
    public double moveToX, moveToY, moveToZ;
    public double moveSpeed;
    public float moveForward;

    // MOUNT
    public boolean mounted;
    public String mountMode = "-";
    public double horseAttr;
    public double horseDxz;
    public boolean horseCollided;
    public boolean horseOnGround;
    public boolean mountWedged;

    // AI / FOLLOW
    public String runningTasks = "";
    public int steeringBlockedTicks = -1;
    /** -1 = no EntityAIFollowGroupLeader running / no leader currently resolved; 0 = leader tracked
     *  virtually; 1 = leader is a loaded entity. Flip-flopping between 0 and 1 tick to tick is exactly
     *  the "leader keeps materializing/virtualizing" signature. */
    public int followLeaderLoaded = -1;
    public double followLeaderX, followLeaderY, followLeaderZ;

    /**
     * Fills this sample from the entity's current state. Cheap getters only - runs every few ticks
     * for every loaded traveller.
     *
     * <p>{@code measuredDxz} is the per-tick horizontal displacement the caller measured against its
     * own previous sample. It has to come from outside: an entity's {@code prevPos} (and
     * {@code lastTickPos}) are both copied from {@code pos} at the very start of its tick, before
     * anything moves it, so reading them here returns exactly zero every time - which silently made
     * every unmounted traveller look permanently stalled and fired automatic captures on entities
     * that were walking perfectly. Only the mounted case worked, by borrowing the horse's delta (the
     * vehicle ticks before its passenger, so its prevPos is genuinely from before the move).
     */
    public void capture(EntityTraveller entity, int tickCounter, double measuredDxz) {
        this.tick = tickCounter;

        this.x = entity.posX;
        this.y = entity.posY;
        this.z = entity.posZ;
        this.yaw = entity.rotationYaw;
        this.headYaw = entity.rotationYawHead;
        this.bodyYaw = entity.renderYawOffset;
        this.headBy = entity.getHeadWriters();
        this.look = entity.describeGaze();
        this.dxz = measuredDxz;
        this.onGround = entity.onGround;
        this.collided = entity.collidedHorizontally;

        Path path = entity.getNavigator().getPath();
        this.hasPath = path != null && !path.isFinished();
        if (this.hasPath) {
            this.pathIdx = path.getCurrentPathIndex();
            this.pathLen = path.getCurrentPathLength();
            PathPoint node = this.pathIdx < this.pathLen ? path.getPathPointFromIndex(this.pathIdx) : null;
            if (node != null) {
                this.nodeX = node.x;
                this.nodeY = node.y;
                this.nodeZ = node.z;
            }
            PathPoint end = path.getFinalPathPoint();
            if (end != null) {
                this.destX = end.x;
                this.destY = end.y;
                this.destZ = end.z;
            }
        } else {
            this.pathIdx = this.pathLen = 0;
        }

        this.moveToX = entity.getMoveHelper().getX();
        this.moveToY = entity.getMoveHelper().getY();
        this.moveToZ = entity.getMoveHelper().getZ();
        this.moveSpeed = entity.getMoveHelper().getSpeed();
        this.moveForward = entity.moveForward;

        this.mounted = entity.isMountedOnBondedMount();
        this.mountMode = entity.getMountUUID() == null ? "-" : entity.getMountMode().name();
        if (this.mounted) {
            Entity ride = entity.getRidingEntity();
            double hdx = ride.posX - ride.prevPosX;
            double hdz = ride.posZ - ride.prevPosZ;
            this.horseDxz = Math.sqrt(hdx * hdx + hdz * hdz);
            this.horseCollided = ride.collidedHorizontally;
            this.horseOnGround = ride.onGround;
            this.horseAttr = ride instanceof net.minecraft.entity.EntityLivingBase
                    ? ((net.minecraft.entity.EntityLivingBase) ride)
                    .getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue()
                    : 0.0D;
            this.mountWedged = TravellerMountSupport.isMountWedged(entity);
            this.dxz = this.horseDxz;
        } else {
            this.horseDxz = 0.0D;
            this.horseCollided = false;
            this.horseOnGround = false;
            this.horseAttr = 0.0D;
            this.mountWedged = false;
        }

        captureTasks(entity);
    }

    private void captureTasks(EntityTraveller entity) {
        StringBuilder sb = new StringBuilder();
        this.steeringBlockedTicks = -1;
        this.followLeaderLoaded = -1;
        for (EntityAITasks.EntityAITaskEntry entry : entity.tasks.taskEntries) {
            if (!entry.using) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(entry.action.getClass().getSimpleName());
            if (entry.action instanceof EntityAIFollowLeader) {
                this.steeringBlockedTicks = ((EntityAIFollowLeader) entry.action).getSteeringBlockedTicks();
            } else if (entry.action instanceof EntityAIFollowGroupLeader) {
                EntityAIFollowGroupLeader task = (EntityAIFollowGroupLeader) entry.action;
                this.steeringBlockedTicks = task.getSteeringBlockedTicks();
                Boolean loaded = task.isLeaderLoaded();
                net.minecraft.util.math.Vec3d pos = task.getLeaderPosition();
                if (loaded != null && pos != null) {
                    this.followLeaderLoaded = loaded ? 1 : 0;
                    this.followLeaderX = pos.x;
                    this.followLeaderY = pos.y;
                    this.followLeaderZ = pos.z;
                }
            }
        }
        this.runningTasks = sb.length() == 0 ? "idle" : sb.toString();
    }

    /**
     * The one line this sample becomes. Sections are pipe-separated and every value is
     * {@code key=value}, so a channel can be dropped without moving anything else - readable to a
     * person, and a two-line regex to a script.
     */
    public String format(EnumSet<TraceChannel> channels) {
        StringBuilder sb = new StringBuilder(200);
        sb.append("S ").append(tick);

        if (channels.contains(TraceChannel.MOVE)) {
            sb.append(" move:pos=").append(f2(x)).append(',').append(f2(y)).append(',').append(f2(z))
                    .append(" dxz=").append(f3(dxz))
                    .append(" yaw=").append(String.format(Locale.ROOT, "%.1f", yaw))
                    .append(" head=").append(String.format(Locale.ROOT, "%.1f", headYaw))
                    .append(" body=").append(String.format(Locale.ROOT, "%.1f", bodyYaw))
                    .append(" headBy=").append(headBy)
                    .append(" look=").append(look)
                    .append(" ground=").append(b(onGround))
                    .append(" coll=").append(b(collided));
        }
        if (channels.contains(TraceChannel.PATH)) {
            sb.append(" | path:");
            if (hasPath) {
                sb.append("idx=").append(pathIdx).append('/').append(pathLen)
                        .append(" node=").append(nodeX).append(',').append(nodeY).append(',').append(nodeZ)
                        .append(" dest=").append(destX).append(',').append(destY).append(',').append(destZ);
            } else {
                sb.append("none");
            }
        }
        if (channels.contains(TraceChannel.STEER)) {
            sb.append(" | steer:to=").append(f2(moveToX)).append(',').append(f2(moveToY)).append(',').append(f2(moveToZ))
                    .append(" sp=").append(f2(moveSpeed))
                    .append(" fwd=").append(f3(moveForward));
        }
        if (channels.contains(TraceChannel.MOUNT)) {
            sb.append(" | mount:");
            if (mounted) {
                sb.append("mode=").append(mountMode)
                        .append(" attr=").append(String.format(Locale.ROOT, "%.4f", horseAttr))
                        .append(" hdxz=").append(f3(horseDxz))
                        .append(" hcoll=").append(b(horseCollided))
                        .append(" hground=").append(b(horseOnGround))
                        .append(" wedged=").append(b(mountWedged));
            } else {
                sb.append("mode=").append(mountMode);
            }
        }
        if (channels.contains(TraceChannel.AI)) {
            sb.append(" | ai=").append(runningTasks);
        }
        if (channels.contains(TraceChannel.FOLLOW) && (steeringBlockedTicks >= 0 || followLeaderLoaded >= 0)) {
            sb.append(" | follow:");
            boolean any = false;
            if (steeringBlockedTicks >= 0) {
                sb.append("blocked=").append(steeringBlockedTicks);
                any = true;
            }
            if (followLeaderLoaded >= 0) {
                if (any) sb.append(' ');
                sb.append("leader=").append(followLeaderLoaded == 1 ? "LOADED" : "VIRTUAL")
                        .append('@').append(f2(followLeaderX)).append(',').append(f2(followLeaderY))
                        .append(',').append(f2(followLeaderZ));
            }
        }
        return sb.toString();
    }

    /** Copies another sample into this one, so a ring buffer can reuse its slots. */
    public void copyFrom(TraceSample o) {
        tick = o.tick;
        x = o.x; y = o.y; z = o.z; yaw = o.yaw;
        headYaw = o.headYaw; bodyYaw = o.bodyYaw; headBy = o.headBy; look = o.look; dxz = o.dxz; onGround = o.onGround; collided = o.collided;
        hasPath = o.hasPath; pathIdx = o.pathIdx; pathLen = o.pathLen;
        nodeX = o.nodeX; nodeY = o.nodeY; nodeZ = o.nodeZ;
        destX = o.destX; destY = o.destY; destZ = o.destZ;
        moveToX = o.moveToX; moveToY = o.moveToY; moveToZ = o.moveToZ;
        moveSpeed = o.moveSpeed; moveForward = o.moveForward;
        mounted = o.mounted; mountMode = o.mountMode;
        horseAttr = o.horseAttr; horseDxz = o.horseDxz;
        horseCollided = o.horseCollided; horseOnGround = o.horseOnGround; mountWedged = o.mountWedged;
        runningTasks = o.runningTasks; steeringBlockedTicks = o.steeringBlockedTicks;
        followLeaderLoaded = o.followLeaderLoaded;
        followLeaderX = o.followLeaderX; followLeaderY = o.followLeaderY; followLeaderZ = o.followLeaderZ;
    }

    /** True when the entity is asking to move and getting nowhere - the shape of every stuck report. */
    public boolean isStalled() {
        return moveForward > 0.02F && dxz < 0.02D;
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static char b(boolean v) {
        return v ? '1' : '0';
    }
}
