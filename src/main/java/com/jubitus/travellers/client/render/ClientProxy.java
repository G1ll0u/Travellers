package com.jubitus.travellers.client.render;

import com.jubitus.travellers.entity.travellers.*;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientProxy {

    public static void registerRenderers() {
        RenderingRegistry.registerEntityRenderingHandler(EntityTraveller.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerRaider.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerMerchant.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerWorker.class, RenderTraveller::new);

        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerDog.class, manager ->
                new net.minecraft.client.renderer.entity.RenderWolf(manager));

        BackpackStyles.registerAll();
    }
}

