package com.jubitus.travellers.mount.client;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.render.ModelFlattener;
import com.jubitus.travellers.unit.render.RenderLodHelper;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/** Beyond {@code mountLodMinDistance}, an adult horse ridden by one of our units is drawn in one buffer. */
@SideOnly(Side.CLIENT)
public final class HorseLod {

    private HorseLod() {
    }

    public static boolean shouldFlatten(Entity entity) {
        if (entity.getClass() != EntityHorse.class || ((EntityHorse) entity).isChild()) return false;
        List<Entity> passengers = entity.getPassengers();
        return !passengers.isEmpty() && passengers.get(0) instanceof EntityTraveller
                && RenderLodHelper.isBeyond(entity, TravellersConfig.ENTITY_RENDER_LOD.mountLodMinDistance);
    }

    public static void draw(Entity entity, ModelRenderer[] bareParts, ModelRenderer[] saddledParts, float scale) {
        ModelFlattener.draw(((EntityHorse) entity).isHorseSaddled() ? saddledParts : bareParts, scale);
    }
}
