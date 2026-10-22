package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A mounted hired worker given a Go order along a flat strip cut by a trench jumps it: it arrives on the far
 * side and its horse never drops into the trench. The strip is built in the arena and put back afterwards.
 */
public final class MountGapScenario extends AutotestScenario {

    private static final int LENGTH = 32;
    private static final int HALF_WIDTH = 3;
    /** The angled variant starts this far to one side of the strip's axis and ends as far to the other. */
    private static final int ANGLED_OFFSET = 9;
    private static final int HEADROOM = 12;
    private static final int TRENCH_AT = 14;
    private static final int TRENCH_DEPTH = 3;
    private static final int ARRIVAL_RADIUS = 4;

    private final int trenchWidth;
    private final boolean angled;
    private final Map<BlockPos, IBlockState> saved = new LinkedHashMap<>();
    private UUID workerId;
    private BlockPos goal;
    private int floorY;
    private boolean fellIn;

    public MountGapScenario(int trenchWidth, boolean angled) {
        this.trenchWidth = trenchWidth;
        this.angled = angled;
    }

    @Override
    public String name() {
        return "mount-gap-" + trenchWidth + (angled ? "-angled" : "");
    }

    @Override
    public int budgetSeconds() {
        return 60;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        fellIn = false;
        WorldServer ws = arena.world;
        int sx = arena.centerX + attempt * 16, sz = arena.centerZ;
        int surface = ColumnGround.terrain(ws, sx, sz, true);
        if (surface == ColumnGround.NONE) return Outcome.skip("no ground at " + sx + " " + sz);
        BlockPos start = new BlockPos(sx, surface, sz);
        floorY = surface;
        int sign = attempt % 2 == 0 ? 1 : -1;
        int halfWidth = angled ? ANGLED_OFFSET + 1 : HALF_WIDTH;
        int offset = angled ? ANGLED_OFFSET : 0;
        for (int a = -2; a <= LENGTH + 2; a++) {
            for (int s = -halfWidth - 1; s <= halfWidth + 1; s++) {
                int x = start.getX() + sign * a, z = start.getZ() + s;
                boolean trench = a >= TRENCH_AT && a < TRENCH_AT + trenchWidth && Math.abs(s) <= halfWidth;
                for (int y = floorY - TRENCH_DEPTH; y <= floorY + HEADROOM; y++) {
                    boolean solid = y < floorY - TRENCH_DEPTH + 1 || (y <= floorY && !trench);
                    set(ws, new BlockPos(x, y, z), solid ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                }
            }
        }
        goal = new BlockPos(start.getX() + sign * LENGTH, floorY + 1, start.getZ() + offset);
        EntityTravellerWorker w = spawnHiredWorker(ws, new BlockPos(start.getX(), floorY + 1, start.getZ() - offset));
        if (w == null) return Outcome.fail("spawn refused at " + start.getX() + " " + start.getZ());
        workerId = w.getUniqueID();
        if (!TravellerMountSupport.bondNewMount(w)) return Outcome.fail("no horse could be bonded");
        w.trips.startGoTo(goal, w.dimension);
        return null;
    }

    private void set(WorldServer ws, BlockPos pos, IBlockState state) {
        saved.putIfAbsent(pos, ws.getBlockState(pos));
        ws.setBlockState(pos, state, 2);
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        EntityTraveller body = loaded(ws, workerId);
        if (body == null) return Outcome.fail("the worker is not loaded (" + where(ws, workerId) + ")");
        EntityHorse horse = TravellerMountSupport.getBondedHorse(body);
        if (horse != null && horse.posY < floorY) fellIn = true;
        if (body.posY < floorY) fellIn = true;
        if (horizontalDistance(new double[]{body.posX, body.posY, body.posZ}, goal) > ARRIVAL_RADIUS) return Outcome.PENDING;
        if (fellIn) return Outcome.fail("crossed through the trench instead of jumping it");
        return body.isRiding() ? Outcome.PASS : Outcome.fail("arrived on foot");
    }

    @Override
    public String describeState(WorldServer ws) {
        EntityTraveller body = loaded(ws, workerId);
        if (body == null) return where(ws, workerId);
        return String.format(java.util.Locale.ROOT, "at %.1f %.1f %.1f, goal %d %d, riding=%b mode=%s fellIn=%b",
                body.posX, body.posY, body.posZ, goal.getX(), goal.getZ(), body.isRiding(),
                body.mountState.getMountMode(), fellIn);
    }

    @Override
    protected void restoreWorld(WorldServer ws) {
        for (Map.Entry<BlockPos, IBlockState> e : saved.entrySet()) ws.setBlockState(e.getKey(), e.getValue(), 2);
        saved.clear();
    }
}
