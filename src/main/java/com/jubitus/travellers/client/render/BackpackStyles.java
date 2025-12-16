package com.jubitus.travellers.client.render;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

public final class BackpackStyles {

    private static final ResourceLocation BACKPACK_TEXTURE =
            new ResourceLocation("travellers", "textures/entity/backpack.png");

    private static final ResourceLocation BAMBOO_TEXTURE =
            new ResourceLocation("travellers", "textures/entity/bamboo.png");

    private static final ResourceLocation CHUSPAS_TEXTURE =
            new ResourceLocation("travellers", "textures/entity/chuspas.png");

    private static final java.util.Set<String> BAMBOO_CULTURES = new java.util.HashSet<>(
            java.util.Arrays.asList("japanese", "song", "daiviet")
    );

    private static final java.util.Set<String> CHUSPAS_CULTURES = new java.util.HashSet<>(
            java.util.Arrays.asList("inca", "huari")
    );

    public static void registerAll() {

        // DEFAULT backpack (always exists)
        BackpackRegistry.register(new SimpleStyle(
                "default",
                true,
                e -> false, // IMPORTANT: default must NOT match
                BACKPACK_TEXTURE,
                new ModelBackpack(),
                (renderer) -> {
                    GlStateManager.translate(0.0F, -0.15F, 0.235F);
                    GlStateManager.rotate(-9.0F, 1.0F, 0.0F, 0.0F);
                    GlStateManager.scale(1.15F, 1.15F, 1.15F);
                }
        ));


        // BAMBOO
        BackpackRegistry.register(new SimpleStyle(
                "bamboo",
                true,
                e -> BAMBOO_CULTURES.contains(getCulture(e)),
                BAMBOO_TEXTURE,
                new ModelBamboo(),
                (renderer) -> {
                    GlStateManager.translate(0.0F, 0.06F, 0.0F);
                    GlStateManager.rotate(-6.0F, 1.0F, 0.0F, 0.0F);
                }
        ));

        // CHUSPAS
        BackpackRegistry.register(new SimpleStyle(
                "chuspas",
                false,
                e -> CHUSPAS_CULTURES.contains(getCulture(e)),
                CHUSPAS_TEXTURE,
                new ModelChuspas(),
                (renderer) -> {
                    GlStateManager.translate(0.00F, 0.0F, 0.02F);
                    GlStateManager.rotate(-4.0F, 1.0F, 0.0F, 0.0F);

                    GlStateManager.scale(1.00F, 1.00F, 1.00F);     // <-- bigger globally for this backpack
                }

        ));
    }

    private static String getCulture(EntityTraveller e) {
        try {
            String c = e.getCultureId();
            return c == null ? "" : c.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ex) {
            return "";
        }
    }

    // Tiny helper style implementation
    private static final class SimpleStyle implements IBackpackStyle {

        interface PoseApplier { void apply(RenderTraveller r); }
        interface Matcher { boolean matches(EntityTraveller e); }

        private final String id;
        private final boolean hideable;
        private final Matcher matcher;
        private final ResourceLocation texture;
        private final net.minecraft.client.model.ModelBase model;
        private final PoseApplier pose;

        SimpleStyle(String id, boolean hideable, Matcher matcher,
                    ResourceLocation texture, net.minecraft.client.model.ModelBase model,
                    PoseApplier pose) {
            this.id = id;
            this.hideable = hideable;
            this.matcher = matcher;
            this.texture = texture;
            this.model = model;
            this.pose = pose;
        }

        @Override public String id() { return id; }
        @Override public boolean isHideable() { return hideable; }
        @Override public boolean matches(EntityTraveller e) { return matcher.matches(e); }
        @Override public ResourceLocation texture(EntityTraveller e) { return texture; }

        @Override
        public void render(RenderTraveller renderer, EntityTraveller e,
                           float limbSwing, float limbSwingAmount, float ageInTicks,
                           float netHeadYaw, float headPitch, float scale) {

            pose.apply(renderer);

            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -1.0F); // pull slightly toward camera

            model.render(e, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale);

            GlStateManager.doPolygonOffset(0.0F, 0.0F);
            GlStateManager.disablePolygonOffset();
        }
    }
}
