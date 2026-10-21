package com.jubitus.travellers.raid;

import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.sound.ModSounds;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketSoundEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** What the raid's player sees and hears: the boss bar for the whole raid, a toast and a horn per wave, the end. */
final class RaidAnnouncer {

    /** Close enough to keep full volume and escape terrain muffling, far enough to be heard from the wave's side. */
    private static final double HORN_DISTANCE = 6.0;

    /** One bar per running raid, by raid id; rebuilt after a restart, never saved. */
    private static final Map<UUID, BossInfoServer> BARS = new HashMap<>();

    private RaidAnnouncer() {
    }

    /** @param angle radians from the player toward where the wave appeared */
    static void waveLaunched(EntityPlayerMP player, int wave, int waves, @Nullable NoticePlace place, double angle) {
        if (wave == 0) {
            NotificationService.notifyAt(player, NotificationDefs.RAID_INCOMING, place);
        } else {
            NotificationService.notifyAt(player, NotificationDefs.RAID_WAVE, place,
                    String.valueOf(wave + 1), String.valueOf(waves));
        }
        play(player, ModSounds.RAID_HORN, player.posX + Math.cos(angle) * HORN_DISTANCE,
                player.posZ + Math.sin(angle) * HORN_DISTANCE, 1.0F);
        FMLLog.log(Level.INFO, "[Travellers/Raid] wave %d/%d on %s, horn %s", wave + 1, waves, player.getName(),
                ModSounds.RAID_HORN == null ? "not registered (no clip)" : "sent");
    }

    /** @param player null when the raid's player is offline: the notice waits for their return, no sound */
    static void ended(MinecraftServer server, UUID raidId, UUID playerId, @Nullable EntityPlayerMP player,
                      boolean repelled) {
        removeBar(raidId);
        NotificationService.notifyOffline(server, playerId,
                repelled ? NotificationDefs.RAID_REPELLED : NotificationDefs.RAID_SCATTERED);
        if (repelled && player != null) play(player, ModSounds.RAID_END, player.posX, player.posZ, 1.0F);
    }

    /** Shows the bar to the raid's player only while they are there; the gauge is the current wave still standing. */
    static void updateBar(UUID raidId, @Nullable EntityPlayerMP player, int wave, int waves, int alive, int launched) {
        BossInfoServer bar = BARS.computeIfAbsent(raidId, id -> new BossInfoServer(
                new TextComponentTranslation("travellers.raid.bar", 1, 1), BossInfo.Color.RED, BossInfo.Overlay.PROGRESS));
        bar.setName(new TextComponentTranslation("travellers.raid.bar", wave + 1, waves));
        bar.setPercent(launched <= 0 ? 0.0F : Math.min(1.0F, alive / (float) launched));
        for (EntityPlayerMP p : new ArrayList<>(bar.getPlayers())) {
            if (p != player) bar.removePlayer(p);
        }
        if (player != null && !bar.getPlayers().contains(player)) bar.addPlayer(player);
    }

    static void removeBar(UUID raidId) {
        BossInfoServer bar = BARS.remove(raidId);
        if (bar == null) return;
        for (EntityPlayerMP p : new ArrayList<>(bar.getPlayers())) bar.removePlayer(p);
    }

    /** Server stopping: bars belong to that server's players. */
    static void clearBars() {
        BARS.clear();
    }

    private static void play(EntityPlayerMP player, @Nullable SoundEvent sound, double x, double z, float volume) {
        if (sound == null) return;
        player.connection.sendPacket(new SPacketSoundEffect(sound, SoundCategory.HOSTILE,
                x, player.posY + player.getEyeHeight(), z, volume, 1.0F));
    }
}
