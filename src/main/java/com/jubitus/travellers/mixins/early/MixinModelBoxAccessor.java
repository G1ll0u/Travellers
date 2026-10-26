package com.jubitus.travellers.mixins.early;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to a box's faces, for {@link com.jubitus.travellers.unit.render.ModelFlattener}. */
@Mixin(ModelBox.class)
public interface MixinModelBoxAccessor {

    @Accessor("quadList")
    TexturedQuad[] travellers$getQuadList();
}
