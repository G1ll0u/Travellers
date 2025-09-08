package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientProxy {

    public static void registerRenderers() {
        RenderingRegistry.registerEntityRenderingHandler(EntityTraveller.class,
                RenderTraveller::new);
    }
}

