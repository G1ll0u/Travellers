package com.jubitus.travellers.marker;

import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public enum WorkerMarkStyle {
    PLAIN(
            "plain",
            "travellers.worker.mark.style.plain",
            new ResourceLocation("travellers", "textures/entity/worker_mark/plain.png"),
            new ResourceLocation("travellers", "textures/entity/worker_mark/plain_leader.png")
    ),
    DOUBLE(
            "double",
            "travellers.worker.mark.style.double",
            new ResourceLocation("travellers", "textures/entity/worker_mark/double.png"),
            new ResourceLocation("travellers", "textures/entity/worker_mark/double_leader.png")
    ),
    CHEVRON(
            "chevron",
            "travellers.worker.mark.style.chevron",
            new ResourceLocation("travellers", "textures/entity/worker_mark/chevron.png"),
            new ResourceLocation("travellers", "textures/entity/worker_mark/chevron_leader.png")
    );

    private static final Map<String, WorkerMarkStyle> BY_ID = new HashMap<>();

    static {
        for (WorkerMarkStyle style : values()) {
            BY_ID.put(style.id, style);
        }
    }

    public final String id;
    public final String i18nKey;
    public final ResourceLocation followerTexture;
    public final ResourceLocation leaderTexture;

    WorkerMarkStyle(String id, String i18nKey, ResourceLocation followerTexture, ResourceLocation leaderTexture) {
        this.id = id;
        this.i18nKey = i18nKey;
        this.followerTexture = followerTexture;
        this.leaderTexture = leaderTexture;
    }

    public ResourceLocation getTexture(boolean leader) {
        return leader ? leaderTexture : followerTexture;
    }

    public static WorkerMarkStyle byId(@Nullable String id) {
        WorkerMarkStyle style = BY_ID.get(id);
        return style != null ? style : PLAIN;
    }
}