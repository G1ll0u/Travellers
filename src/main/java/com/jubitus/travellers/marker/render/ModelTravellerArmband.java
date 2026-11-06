package com.jubitus.travellers.marker.render;

import com.jubitus.travellers.unit.render.models.ModelSimpleHumanoid;

// Same model as the body, so the band follows every custom arm pose (drinking, bow, shield), like armor does.
public class ModelTravellerArmband extends ModelSimpleHumanoid {

    public ModelTravellerArmband() {
        // 0.75F gives enough physical separation from the body mesh (0.0F) to avoid
        // z-fighting without looking puffy. Vanilla outer armor uses 1.0F.
        super(0.75F, 64, 32);
    }
}
