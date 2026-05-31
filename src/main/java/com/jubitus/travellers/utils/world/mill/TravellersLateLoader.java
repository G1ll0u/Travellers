package com.jubitus.travellers.utils.world.mill;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

public class TravellersLateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        System.out.println("[Travellers] ILateMixinLoader called! Queuing Travellers.millmix.json");
        return Collections.singletonList("mixins.travellers.json");
    }
}