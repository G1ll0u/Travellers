package com.jubitus.travellers;

import com.jubitus.travellers.unit.render.backpack.BackpackStyles;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.jubitus.travellers.unit.render.RenderTraveller;

@SideOnly(Side.CLIENT)
public class ClientProxy {

    public static void registerRenderers() {
        RenderingRegistry.registerEntityRenderingHandler(EntityTraveller.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerRaider.class, RenderTraveller::new);
        RenderingRegistry.registerEntityRenderingHandler(EntityTravellerWorker.class, RenderTraveller::new);

        BackpackStyles.registerAll();
    }
}

