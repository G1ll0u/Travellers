package com.jubitus.travellers.notify;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import javax.annotation.Nullable;

/**
 * Forwards vanilla's own death-message i18n key + args through our notification pipeline instead
 * of hand-authoring our own death-cause text, so death notices localize exactly like vanilla's own
 * ("was slain by", "fell from a high place", ...) in every language the client has installed.
 */
public final class VanillaDeathMessage {

    private VanillaDeathMessage() {
    }

    public static final class Resolved {
        public final String i18nKey;
        public final String[] args;

        Resolved(String i18nKey, String[] args) {
            this.i18nKey = i18nKey;
            this.args = args;
        }
    }

    /** Null if there's no DamageSource or its death message isn't a plain translation (rare, e.g. a
     *  custom ITextComponent) - callers should fall back to their own generic message in that case. */
    @Nullable
    public static Resolved from(EntityLivingBase victim, @Nullable DamageSource cause) {
        if (cause == null) return null;
        try {
            ITextComponent msg = cause.getDeathMessage(victim);
            if (!(msg instanceof TextComponentTranslation)) return null;
            TextComponentTranslation t = (TextComponentTranslation) msg;
            Object[] raw = t.getFormatArgs();
            String[] args = new String[raw.length];
            for (int i = 0; i < raw.length; i++) {
                args[i] = raw[i] instanceof ITextComponent
                        ? ((ITextComponent) raw[i]).getUnformattedText()
                        : String.valueOf(raw[i]);
            }
            return new Resolved(t.getKey(), args);
        } catch (Exception e) {
            return null;
        }
    }
}
