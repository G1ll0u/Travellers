package com.jubitus.travellers.utils.world.mill;

import net.minecraft.entity.player.EntityPlayer;
import org.millenaire.common.item.TradeGood;
import org.millenaire.common.village.Building;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.LinkedHashMap;

@Mixin(value = Building.class, remap = false)
public abstract class MixinBuildingSafeBuyingPrice {


    @Final
    @Shadow(remap = false)
    private HashMap<String, LinkedHashMap<TradeGood, Integer>> shopBuys;

    @Inject(method = "getBuyingPrice", at = @At("HEAD"), cancellable = true, remap = false)
    private void travellers$nullSafeBuyingPrice(TradeGood g, EntityPlayer player,
                                                CallbackInfoReturnable<Integer> cir) {
        // Mirror Millénaire’s intent: “no price” => 0
        if (player == null || g == null || shopBuys == null) {
            cir.setReturnValue(0);
            return;
        }

        String name = player.getName(); // func_70005_c_()
        LinkedHashMap<TradeGood, Integer> map = shopBuys.get(name);
        if (map == null) {
            cir.setReturnValue(0);
            return;
        }

        Integer price = map.get(g);
        cir.setReturnValue(price == null ? 0 : price.intValue());
    }
}
