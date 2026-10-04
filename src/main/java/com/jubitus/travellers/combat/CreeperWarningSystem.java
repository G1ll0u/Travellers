package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class CreeperWarningSystem {

    // playerEntityId -> world time of last warning played
    private static final Map<Integer, Long> lastWarnByPlayer = new HashMap<>();

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        if (!TravellersConfig.SOUND.creeperWarnEnabled) return;
        // Check once per second
        if (event.world.getTotalWorldTime() % 20 != 0) return;

        for (EntityPlayer player : event.world.playerEntities) {
            checkForPlayer(event.world, player);
        }
    }

    private static void checkForPlayer(World world, EntityPlayer player) {
        long now = world.getTotalWorldTime();
        Long lastWarn = lastWarnByPlayer.get(player.getEntityId());
        if (lastWarn != null && now - lastWarn < TravellersConfig.SOUND.creeperWarnCooldownTicks) return;

        double creeperRadius = TravellersConfig.SOUND.creeperWarnCreeperRadius;
        AxisAlignedBB creeperBox = player.getEntityBoundingBox().grow(creeperRadius);
        List<EntityCreeper> creepers = world.getEntitiesWithinAABB(EntityCreeper.class, creeperBox,
                c -> !c.isDead && isOutsideFOV(player, c));
        if (creepers.isEmpty()) return;

        double travRadius = TravellersConfig.SOUND.creeperWarnTravellerRadius;
        AxisAlignedBB travBox = player.getEntityBoundingBox().grow(travRadius);
        List<EntityTraveller> candidates = world.getEntitiesWithinAABB(EntityTraveller.class, travBox,
                t -> !(t instanceof EntityTravellerRaider) && !t.isDead && !t.voice.isLocked());
        if (candidates.isEmpty()) return;

        // Only the nearest traveller warns
        EntityTraveller nearest = null;
        double minDist = Double.MAX_VALUE;
        for (EntityTraveller t : candidates) {
            double d = t.getDistanceSq(player);
            if (d < minDist) { minDist = d; nearest = t; }
        }
        if (nearest == null) return;

        // Traveller must have line of sight to at least one threatening creeper
        boolean canSeeCreeper = false;
        for (EntityCreeper c : creepers) {
            if (nearest.canEntityBeSeen(c)) { canSeeCreeper = true; break; }
        }
        if (!canSeeCreeper) return;

        nearest.voice.playCreeperWarning();
        lastWarnByPlayer.put(player.getEntityId(), now);
    }

    private static boolean isOutsideFOV(EntityPlayer player, EntityCreeper creeper) {
        Vec3d look = player.getLookVec();
        double dx = creeper.posX - player.posX;
        double dy = (creeper.posY + creeper.height * 0.5) - (player.posY + player.getEyeHeight());
        double dz = creeper.posZ - player.posZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return false;
        double dot = (look.x * dx + look.y * dy + look.z * dz) / len;
        // Outside ~120° total FOV (cos(60°) = 0.5)
        return dot < 0.5;
    }
}
