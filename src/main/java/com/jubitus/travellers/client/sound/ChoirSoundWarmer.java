package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.entity.culture.choir.ChoirIndex;
import com.jubitus.travellers.entity.culture.choir.ChoirRegistry;
import com.jubitus.travellers.entity.culture.choir.ChoirSongDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;

@SideOnly(Side.CLIENT)
public final class ChoirSoundWarmer {

    private ChoirSoundWarmer() {}

    /**
     * Méthode appelée une seule fois à la fin du chargement de Minecraft.
     * Bloque volontairement l'écran de chargement Mojang pendant le décodage I/O,
     * garantissant un jeu et un menu principal 100% fluides par la suite.
     */
    public static void warmUpAtLaunch() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() == null) return;

        // 1. Récupération de tous les sons à précharger
        java.util.List<net.minecraft.util.SoundEvent> soundsToWarm = collectAllStems();
        if (soundsToWarm.isEmpty()) return;

        // 2. Traitement séquentiel de chaque son
        for (net.minecraft.util.SoundEvent stem : soundsToWarm) {
            if (stem == null || stem.getRegistryName() == null) continue;

            try {
                // Création du record audio fictif (inaudible à la position 0,0,0)
                PositionedSoundRecord snd = new PositionedSoundRecord(
                        stem.getRegistryName(), SoundCategory.PLAYERS,
                        0.001f, 1.0f, false, 0,
                        ISound.AttenuationType.NONE,
                        0f, 0f, 0f);

                // On force le gestionnaire à charger/lire le son
                mc.getSoundHandler().playSound(snd);
                // On l'arrête immédiatement. PaulCode (le moteur audio) aura quand même
                // mis en cache les données décodées de l'OGG.
                mc.getSoundHandler().stopSound(snd);

            } catch (Throwable ignored) {}
        }
    }

    private static java.util.List<net.minecraft.util.SoundEvent> collectAllStems() {
        java.util.List<net.minecraft.util.SoundEvent> list = new java.util.ArrayList<>();
        ChoirIndex idx = ChoirIndex.loadOrDefault();
        if (idx.songs == null) return list;

        for (Map.Entry<String, Map<String, java.util.List<ChoirIndex.SongEntry>>> langEntry : idx.songs.entrySet()) {
            if (langEntry.getValue() == null) continue;
            String lang = langEntry.getKey().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, java.util.List<ChoirIndex.SongEntry>> persEntry : langEntry.getValue().entrySet()) {
                if (persEntry.getValue() == null) continue;
                String pers = persEntry.getKey().toLowerCase(Locale.ROOT);
                for (ChoirSongDef song : ChoirRegistry.getSongs(lang, pers)) {
                    for (int i = 0; i < song.stemCount(); i++) {
                        net.minecraft.util.SoundEvent ev = song.stem(i);
                        if (ev != null) list.add(ev);
                    }
                }
            }
        }
        return list;
    }
}