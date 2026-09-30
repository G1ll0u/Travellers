package com.jubitus.travellers.world.virtualization;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLLog;

public final class VirtualizationLog {

    private VirtualizationLog() {
    }

    public static void virtualize(String reason, Entity e) {
        com.jubitus.travellers.debug.profile.MovementProfiler.virtualized(reason);
        if (!TravellersConfig.MATERIALIZATION.logEvents || e == null) return;
        log("VIRTUALIZE", reason, e.world, e.getName(), e.posX, e.posY, e.posZ);
    }

    public static void materialize(World world, VirtualTravellerRecord r) {
        if (!TravellersConfig.MATERIALIZATION.logEvents) return;
        log("MATERIALIZE", r.entityClass, world, r.name, r.posX, r.posY, r.posZ);
    }

    private static void log(String kind, String reason, World world, String name, double x, double y, double z) {
        EntityPlayer p = world.getClosestPlayer(x, y, z, -1.0, false);
        String dist = p == null ? "no player" : String.format("%.0f blocks from nearest player", p.getDistance(x, y, z));
        FMLLog.log.info("[Travellers/VirtLog] {} {} ({}) at ({}, {}, {}) - {}",
                kind, name, reason, (int) x, (int) y, (int) z, dist);
    }
}
