package com.jubitus.travellers.zone;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Reports block changes inside a zone so its beds/chests are rescanned right away (see {@link ZoneRoleRescan}). */
@Mod.EventBusSubscriber
public final class ZoneBlockGuards {

    private ZoneBlockGuards() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onBreak(BlockEvent.BreakEvent e) {
        note(e.getWorld(), e.getPos());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onPlace(BlockEvent.PlaceEvent e) {
        note(e.getWorld(), e.getPos());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onExplosion(ExplosionEvent.Detonate e) {
        for (BlockPos p : e.getAffectedBlocks()) note(e.getWorld(), p);
    }

    private static void note(World w, BlockPos pos) {
        if (w instanceof WorldServer) ZoneRoleRescan.noteBlockChange((WorldServer) w, pos);
    }
}
