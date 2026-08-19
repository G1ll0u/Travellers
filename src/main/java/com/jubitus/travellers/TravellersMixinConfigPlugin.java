package com.jubitus.travellers;

import com.jubitus.travellers.mixins.MixinNpcFactionHostility;
import net.minecraftforge.fml.common.Loader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates mixins that target an optional third-party mod's classes. Every other mixin in this
 * config targets either vanilla or Millenaire (a required-after dependency, always present), so
 * this plugin only needs to special-case the ones that don't - currently just
 * {@link MixinNpcFactionHostility}, whose target (Ancient Warfare 2's NpcFaction) may not exist
 * on the classpath at all. shouldApplyMixin is called with plain class-name strings before the
 * Mixin subsystem ever attempts to resolve/load either class, so returning false here is what
 * keeps Travellers running fine without AW2 installed - see AncientWarfare2Compat for the other
 * half of that safety (pure reflection, no direct AW2 type reference outside the mixins
 * package).
 */
public class TravellersMixinConfigPlugin implements IMixinConfigPlugin {

    private static final String AW2_MOD_ID = "ancientwarfarenpc";
    private static final String AW2_GATED_MIXIN = "com.jubitus.travellers.mixins.MixinNpcFactionHostility";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (AW2_GATED_MIXIN.equals(mixinClassName)) {
            return Loader.isModLoaded(AW2_MOD_ID);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
