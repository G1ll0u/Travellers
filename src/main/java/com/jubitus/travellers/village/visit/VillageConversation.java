package com.jubitus.travellers.village.visit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * A few village visitors chatting: a pair face to face, or a small circle facing its centre, with the
 * "speaker" changing every few seconds so the others' eyes move with it. A Millénaire villager can be
 * the other half of a pair (it is never moved; the visitor stands in front of it). Loaded-only, never
 * saved: a member that leaves (fight, end of stay, going offscreen) just drops out.
 */
final class VillageConversation {

    private static final Map<World, List<VillageConversation>> ACTIVE = new WeakHashMap<>();

    private final World world;
    private final List<EntityLivingBase> members = new ArrayList<>(4);
    private final List<Double> angles = new ArrayList<>(4);
    private Vec3d centre;
    /** A villager partner: the conversation is built around where it stands. */
    @Nullable
    private final EntityLivingBase anchor;
    @Nullable
    private Vec3d anchorDir;
    private final long endTick;
    private int speaker;
    private long nextSpeakerTick;
    private int speakerChanges;
    private boolean over;

    private VillageConversation(World world, EntityLivingBase a, EntityLivingBase b, boolean bIsAnchor, int ticks) {
        this.world = world;
        this.endTick = world.getTotalWorldTime() + ticks;
        this.anchor = bIsAnchor ? b : null;
        members.add(a);
        members.add(b);
        centre = new Vec3d((a.posX + b.posX) * 0.5D, a.posY, (a.posZ + b.posZ) * 0.5D);
        if (anchor != null) {
            double dx = a.posX - b.posX;
            double dz = a.posZ - b.posZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            anchorDir = len < 1.0E-3 ? new Vec3d(1, 0, 0) : new Vec3d(dx / len, 0, dz / len);
        }
        respace();
        speaker = world.rand.nextInt(2);
        nextSpeakerTick = world.getTotalWorldTime() + speakerTurnTicks(world.rand);
    }

    static VillageConversation start(EntityTraveller a, EntityLivingBase b, boolean withVillager, int ticks) {
        VillageConversation c = new VillageConversation(a.world, a, b, withVillager, ticks);
        ACTIVE.computeIfAbsent(a.world, w -> new ArrayList<>()).add(c);
        return c;
    }

    /** A conversation near {@code t} with room for one more visitor, or null. */
    @Nullable
    static VillageConversation findJoinable(EntityTraveller t, double radius) {
        List<VillageConversation> list = ACTIVE.get(t.world);
        if (list == null) return null;
        int max = TravellersConfig.VILLAGE_VISIT.talkMaxMembers;
        long now = t.world.getTotalWorldTime();
        VillageConversation best = null;
        double bestD = radius * radius;
        Iterator<VillageConversation> it = list.iterator();
        while (it.hasNext()) {
            VillageConversation c = it.next();
            if (c.isOver(now)) {
                it.remove();
                continue;
            }
            if (c.anchor != null || c.members.size() >= max || c.members.contains(t)) continue;
            if (c.endTick - now < 20L * TravellersConfig.VILLAGE_VISIT.talkMinSeconds) continue;
            double d = t.getDistanceSq(c.centre.x, c.centre.y, c.centre.z);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    boolean join(EntityTraveller t) {
        if (over || anchor != null || members.contains(t)
                || members.size() >= TravellersConfig.VILLAGE_VISIT.talkMaxMembers) return false;
        members.add(t);
        respace();
        return true;
    }

    void leave(EntityLivingBase e) {
        int i = members.indexOf(e);
        if (i < 0) return;
        members.remove(i);
        if (i < speaker) speaker--;
        if (members.size() < 2 || e == anchor) {
            over = true;
            return;
        }
        if (speaker >= members.size()) speaker = 0;
        respace();
    }

    boolean isOver(long now) {
        if (over) return true;
        if (now >= endTick) {
            over = true;
            return true;
        }
        for (int i = members.size() - 1; i >= 0; i--) {
            if (!members.get(i).isEntityAlive()) leave(members.get(i));
            if (over) return true;
        }
        if (anchor != null) {
            double r = TravellersConfig.VILLAGE_VISIT.socialRadius + 2.0D;
            if (members.get(0).getDistanceSq(anchor) > r * r) over = true;
        }
        return over;
    }

    long getEndTick() {
        return endTick;
    }

    /** Where {@code e} should stand: on the pair's line or around the circle, facing the others. */
    @Nullable
    Vec3d slotOf(EntityLivingBase e) {
        double d = TravellersConfig.VILLAGE_VISIT.talkDistance;
        if (anchor != null) {
            if (e == anchor || anchorDir == null) return null;
            return new Vec3d(anchor.posX + anchorDir.x * d, anchor.posY, anchor.posZ + anchorDir.z * d);
        }
        int i = members.indexOf(e);
        if (i < 0) return null;
        double r = radius(members.size(), d);
        double a = angles.get(i);
        return new Vec3d(centre.x + Math.cos(a) * r, centre.y, centre.z + Math.sin(a) * r);
    }

    /** Advances the turn-taking; returns the member currently "speaking". */
    EntityLivingBase speaker(long now) {
        if (now >= nextSpeakerTick && members.size() > 1) {
            int next = world.rand.nextInt(members.size() - 1);
            speaker = next >= speaker ? next + 1 : next;
            nextSpeakerTick = now + speakerTurnTicks(world.rand);
            speakerChanges++;
        }
        return members.get(Math.min(speaker, members.size() - 1));
    }

    int getSpeakerChanges() {
        return speakerChanges;
    }

    /** Who a speaking member looks at: one of the others, stable for the current turn. */
    EntityLivingBase listenerFor(EntityLivingBase speakerEntity) {
        int n = members.size();
        int self = members.indexOf(speakerEntity);
        int pick = (self + 1 + (speakerChanges % Math.max(1, n - 1))) % n;
        if (pick == self) pick = (pick + 1) % n;
        return members.get(pick);
    }

    int size() {
        return members.size();
    }

    private void respace() {
        // Keep everyone in the order they already stand around the centre, evenly spread from the first.
        int n = members.size();
        if (anchor == null && n > 2) {
            double sx = 0, sz = 0;
            for (EntityLivingBase m : members) {
                sx += m.posX;
                sz += m.posZ;
            }
            centre = new Vec3d(sx / n, centre.y, sz / n);
        }
        List<double[]> byAngle = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            EntityLivingBase m = members.get(i);
            byAngle.add(new double[]{Math.atan2(m.posZ - centre.z, m.posX - centre.x), i});
        }
        byAngle.sort((x, y) -> Double.compare(x[0], y[0]));
        angles.clear();
        for (int i = 0; i < n; i++) angles.add(0.0D);
        double base = byAngle.get(0)[0];
        for (int k = 0; k < n; k++) {
            angles.set((int) byAngle.get(k)[1], base + 2.0D * Math.PI * k / n);
        }
    }

    private static double radius(int n, double d) {
        if (n <= 2) return d * 0.5D;
        return d / (2.0D * Math.sin(Math.PI / n));
    }

    private static int speakerTurnTicks(Random rng) {
        return 40 + rng.nextInt(61);
    }
}
