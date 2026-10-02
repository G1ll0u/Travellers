package com.jubitus.travellers.client.render;

import com.jubitus.travellers.client.render.backpack.BackpackStyles;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientProxy {

    public static void registerRenderers() {
        RenderingRegistry.registerEntityRenderingHandler(EntityTraveller.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerRaider.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerWorker.class, RenderTraveller::new);

        BackpackStyles.registerAll();
    }
}

