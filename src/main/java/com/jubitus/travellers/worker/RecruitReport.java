package com.jubitus.travellers.worker;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillSettlements;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The recruiting report the owner reads when the recruiter is back: built on the server, drawn by the client. */
public final class RecruitReport {

    public static final class CultureLine {
        public final String key;
        public final int wanted, alive, inVillages, onRoad, dead;

        CultureLine(String key, int wanted, int alive, int inVillages, int onRoad, int dead) {
            this.key = key;
            this.wanted = wanted;
            this.alive = alive;
            this.inVillages = inVillages;
            this.onRoad = onRoad;
            this.dead = dead;
        }
    }

    public static final class StopLine {
        public final BlockPos pos;
        public final String name;
        /** {@link RecruitLog#PENDING}, VISITED or SKIPPED. */
        public final int state;
        public final int recruits;

        StopLine(BlockPos pos, String name, int state, int recruits) {
            this.pos = pos;
            this.name = name;
            this.state = state;
            this.recruits = recruits;
        }
    }

    public int entityId;
    public String name = "";
    public long durationTicks;
    public int spent;
    public int left;
    public final List<CultureLine> cultures = new ArrayList<>();
    /** In visiting order. */
    public final List<StopLine> stops = new ArrayList<>();
    public final List<BlockPos> roadJoins = new ArrayList<>();
    public final List<Integer> roadJoinCounts = new ArrayList<>();
    public final List<BlockPos> deaths = new ArrayList<>();

    public static RecruitReport of(EntityTravellerWorker worker, RecruitTour tour) {
        World world = worker.world;
        RecruitLog log = tour.log();
        RecruitReport r = new RecruitReport();
        r.entityId = worker.getEntityId();
        r.name = worker.getName();
        r.durationTicks = Math.max(0L, world.getTotalWorldTime() - log.startTick);
        r.spent = log.spent;
        r.left = worker.purse.get();
        for (Map.Entry<String, Integer> e : tour.wanted().entrySet()) {
            int[] c = log.byCulture.getOrDefault(e.getKey(), new int[3]);
            r.cultures.add(new CultureLine(e.getKey(), e.getValue(), tour.found(e.getKey()), c[0], c[1], c[2]));
        }
        for (RecruitPlan.Stop s : tour.stops()) {
            int[] st = log.stops.getOrDefault(s.center.toLong(), new int[2]);
            String name = MillSettlements.nameOrNull(world, s.center);
            r.stops.add(new StopLine(s.center, name != null ? name : "", st[0], st[1]));
        }
        for (long[] j : log.roadJoins) {
            r.roadJoins.add(BlockPos.fromLong(j[0]));
            r.roadJoinCounts.add((int) j[1]);
        }
        for (long p : log.deaths) r.deaths.add(BlockPos.fromLong(p));
        return r;
    }

    public void write(ByteBuf buf) {
        buf.writeInt(entityId);
        ByteBufUtils.writeUTF8String(buf, name);
        buf.writeLong(durationTicks);
        buf.writeInt(spent);
        buf.writeInt(left);
        buf.writeShort(cultures.size());
        for (CultureLine c : cultures) {
            ByteBufUtils.writeUTF8String(buf, c.key);
            buf.writeShort(c.wanted);
            buf.writeShort(c.alive);
            buf.writeShort(c.inVillages);
            buf.writeShort(c.onRoad);
            buf.writeShort(c.dead);
        }
        buf.writeShort(stops.size());
        for (StopLine s : stops) {
            buf.writeLong(s.pos.toLong());
            ByteBufUtils.writeUTF8String(buf, s.name);
            buf.writeByte(s.state);
            buf.writeShort(s.recruits);
        }
        buf.writeShort(roadJoins.size());
        for (int i = 0; i < roadJoins.size(); i++) {
            buf.writeLong(roadJoins.get(i).toLong());
            buf.writeShort(roadJoinCounts.get(i));
        }
        buf.writeShort(deaths.size());
        for (BlockPos p : deaths) buf.writeLong(p.toLong());
    }

    public static RecruitReport read(ByteBuf buf) {
        RecruitReport r = new RecruitReport();
        r.entityId = buf.readInt();
        r.name = ByteBufUtils.readUTF8String(buf);
        r.durationTicks = buf.readLong();
        r.spent = buf.readInt();
        r.left = buf.readInt();
        int n = Math.min(buf.readShort(), 64);
        for (int i = 0; i < n; i++) {
            r.cultures.add(new CultureLine(ByteBufUtils.readUTF8String(buf), buf.readShort(), buf.readShort(),
                    buf.readShort(), buf.readShort(), buf.readShort()));
        }
        n = Math.min(buf.readShort(), 256);
        for (int i = 0; i < n; i++) {
            r.stops.add(new StopLine(BlockPos.fromLong(buf.readLong()), ByteBufUtils.readUTF8String(buf), buf.readByte(), buf.readShort()));
        }
        n = Math.min(buf.readShort(), 256);
        for (int i = 0; i < n; i++) {
            r.roadJoins.add(BlockPos.fromLong(buf.readLong()));
            r.roadJoinCounts.add((int) buf.readShort());
        }
        n = Math.min(buf.readShort(), 256);
        for (int i = 0; i < n; i++) r.deaths.add(BlockPos.fromLong(buf.readLong()));
        return r;
    }
}
