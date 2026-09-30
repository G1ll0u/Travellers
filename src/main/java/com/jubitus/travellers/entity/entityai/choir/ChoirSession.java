package com.jubitus.travellers.entity.entityai.choir;

import com.jubitus.travellers.entity.culture.choir.ChoirSongDef;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChoirSession {
    public final UUID id;
    public final ChoirSongDef song;
    public final List<EntityTraveller> singers;
    public final Vec3d startCenter;
    public final long endTick;
    public final float vol;
    public final float pitch;
    // non-null if triggered by a player pressing the worker button
    @Nullable public final UUID ownerPlayerId;

    // entityId -> stem index in song
    final Map<Integer, Integer> stemByEntityId = new HashMap<>();
    // entityId -> earliest world tick after which to rejoin (set when voice becomes free)
    final Map<Integer, Long> rejoinAt = new HashMap<>();

    private int spreadFailCount = 0;

    ChoirSession(ChoirSongDef song, List<EntityTraveller> singers, long endTick,
                 @Nullable UUID ownerPlayerId, float vol, float pitch) {
        this.id = UUID.randomUUID();
        this.song = song;
        this.singers = singers;
        this.startCenter = centroid(singers);
        this.endTick = endTick;
        this.ownerPlayerId = ownerPlayerId;
        this.vol = vol;
        this.pitch = pitch;
        for (int i = 0; i < singers.size(); i++) {
            stemByEntityId.put(singers.get(i).getEntityId(), i);
        }
    }

    private static Vec3d centroid(List<EntityTraveller> singers) {
        double x = 0, y = 0, z = 0;
        for (EntityTraveller s : singers) { x += s.posX; y += s.posY; z += s.posZ; }
        int n = Math.max(1, singers.size());
        return new Vec3d(x / n, y / n, z / n);
    }

    void incrementSpreadFail() { spreadFailCount++; }
    void resetSpreadFail() { spreadFailCount = 0; }
    int getSpreadFailCount() { return spreadFailCount; }
}
