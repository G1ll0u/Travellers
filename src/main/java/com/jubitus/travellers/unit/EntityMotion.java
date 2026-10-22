package com.jubitus.travellers.unit;

import com.jubitus.travellers.Tags;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one answer to "how far did this entity move per tick", in blocks.
 *
 * <p>{@code pos - prevPos} is useless for a player on the server: every movement packet, on foot or
 * riding, goes through {@code Entity.setPositionAndRotation}, which writes {@code prevPos = pos}. A
 * server player is therefore measured from its own positions, recorded once per tick and averaged
 * over a few ticks (packets arrive unevenly: zero one tick, two the next).
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class EntityMotion {

    private static final int WINDOW = 4;
    private static final double TELEPORT_SQ = 8.0D * 8.0D;
    private static final Map<EntityPlayer, Track> TRACKS = new WeakHashMap<>();

    private EntityMotion() {
    }

    public static double velX(Entity e) {
        Track t = track(e);
        return t != null ? t.vel(0) : e.posX - e.prevPosX;
    }

    public static double velY(Entity e) {
        Track t = track(e);
        return t != null ? t.vel(1) : e.posY - e.prevPosY;
    }

    public static double velZ(Entity e) {
        Track t = track(e);
        return t != null ? t.vel(2) : e.posZ - e.prevPosZ;
    }

    public static double speedXZ(Entity e) {
        double vx = velX(e), vz = velZ(e);
        return Math.sqrt(vx * vx + vz * vz);
    }

    private static Track track(Entity e) {
        return e instanceof EntityPlayer && !e.world.isRemote ? TRACKS.get(e) : null;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) return;
        TRACKS.computeIfAbsent(event.player, p -> new Track()).record(event.player);
    }

    /** The last WINDOW + 1 positions of one player, oldest overwritten first. */
    private static final class Track {
        private final double[][] pos = new double[WINDOW + 1][3];
        private int next;
        private int count;

        void record(EntityPlayer p) {
            if (count > 0) {
                double[] last = pos[(next - 1 + pos.length) % pos.length];
                double dx = p.posX - last[0], dz = p.posZ - last[2];
                // A teleport is not motion: start the window over.
                if (dx * dx + dz * dz > TELEPORT_SQ) count = 0;
            }
            double[] slot = pos[next];
            slot[0] = p.posX;
            slot[1] = p.posY;
            slot[2] = p.posZ;
            next = (next + 1) % pos.length;
            if (count < pos.length) count++;
        }

        double vel(int axis) {
            if (count < 2) return 0.0D;
            int newest = (next - 1 + pos.length) % pos.length;
            int oldest = (next - count + pos.length) % pos.length;
            return (pos[newest][axis] - pos[oldest][axis]) / (count - 1);
        }
    }
}
