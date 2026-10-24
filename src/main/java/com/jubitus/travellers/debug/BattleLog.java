package com.jubitus.travellers.debug;

import com.jubitus.travellers.battle.BattleParticipant;
import com.jubitus.travellers.battle.BattleRecord;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * Always-on battle lifecycle log, {@code logs/travellers-battles.log} (previous server run in {@code .old.log}), for battles with henchmen or players: reopen,
 * merges, pawns gone missing, the end with its reason and headcounts, and owner notices. Capped per minute.
 */
public final class BattleLog {

    private static final int MAX_LINES_PER_MINUTE = 300;

    @Nullable private static BufferedWriter writer;
    private static long minuteStartMs;
    private static int linesThisMinute;
    private static boolean rotated;

    private BattleLog() {
    }

    public static String id(BattleRecord b) {
        return b.id.toString().substring(0, 8);
    }

    /** Alive/fallen per display camp and faction, e.g. {@code 0:OWNER:f91b6f5a=5/0 1:RAIDER=3/1}. */
    public static String counts(BattleRecord b) {
        Map<String, Integer> camps = b.camps(null);
        Map<String, int[]> byKey = new TreeMap<>();
        for (BattleParticipant p : b.participants.values()) {
            String key = camps.get(p.groupKey) + ":" + shortKey(p.groupKey) + (p.isPlayer ? "(player)" : "");
            int[] c = byKey.computeIfAbsent(key, k -> new int[2]);
            c[p.alive ? 0 : 1]++;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : byKey.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue()[0]).append('/').append(e.getValue()[1]);
        }
        return sb.toString();
    }

    public static String shortKey(String groupKey) {
        return groupKey.startsWith(BattleParticipant.OWNER_KEY_PREFIX)
                ? groupKey.substring(0, Math.min(groupKey.length(), BattleParticipant.OWNER_KEY_PREFIX.length() + 8))
                : groupKey;
    }

    /** Logged only for battles with a hired henchman or a player in them. */
    public static void log(@Nullable MinecraftServer server, long tick, BattleRecord b, String event) {
        if (server == null || !involvesPlayers(b)) return;
        write(server, "t=" + tick + " battle=" + id(b) + " at=" + b.anchor.getX() + "," + b.anchor.getZ() + " " + event);
    }

    /** Probe lines: written for every battle, henchmen or not. */
    public static void logAny(@Nullable MinecraftServer server, long tick, BattleRecord b, String event) {
        if (server == null) return;
        write(server, "t=" + tick + " battle=" + id(b) + " at=" + b.anchor.getX() + "," + b.anchor.getZ() + " " + event);
    }

    private static boolean involvesPlayers(BattleRecord b) {
        for (BattleParticipant p : b.participants.values()) {
            if (p.isPlayer || p.groupKey.startsWith(BattleParticipant.OWNER_KEY_PREFIX)) return true;
        }
        return false;
    }

    private static void write(MinecraftServer server, String line) {
        long ms = System.currentTimeMillis();
        if (ms - minuteStartMs >= 60_000L) {
            minuteStartMs = ms;
            linesThisMinute = 0;
        }
        if (++linesThisMinute > MAX_LINES_PER_MINUTE) return;
        try {
            if (writer == null) {
                File file = new File(server.getDataDirectory(), "logs/travellers-battles.log");
                file.getParentFile().mkdirs();
                if (!rotated) {
                    // One session per file: the previous one is kept once as .old.log.
                    rotated = true;
                    File old = new File(file.getParentFile(), "travellers-battles.old.log");
                    if (file.exists() && (!old.exists() || old.delete())) file.renameTo(old);
                }
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
                writer.write("# opened " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
            }
            writer.write(new SimpleDateFormat("HH:mm:ss").format(new Date(ms)) + " " + line + "\n");
            writer.flush();
        } catch (IOException ex) {
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }
}
