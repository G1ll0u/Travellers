package com.jubitus.travellers.world.mill;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

public class TravellersLateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        System.out.println("[Travellers] ILateMixinLoader called! Queuing mixins.travellers.json");
        return Collections.singletonList("mixins.travellers.json");
    }
}