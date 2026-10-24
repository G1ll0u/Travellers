package com.jubitus.travellers.gui;

import com.jubitus.travellers.village.MillMoney;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.Item;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Text in which {@link MillMoney#format} amounts are drawn as coin icons, each after its count.
 * Without an amount every method behaves exactly like the plain {@link FontRenderer} call.
 */
@SideOnly(Side.CLIENT)
public final class MoneyText {

    private static final int ICON = 8;
    private static final int COUNT_GAP = 1;
    private static final int COIN_GAP = 3;

    private MoneyText() {
    }

    public static int width(FontRenderer fr, String text) {
        if (!hasAmount(text)) return fr.getStringWidth(text);
        return layout(fr, text, 0, 0, 0, false);
    }

    /** Draws without shadow, like {@link FontRenderer#drawString(String, int, int, int)}. */
    public static void draw(FontRenderer fr, String text, int x, int y, int color) {
        if (!hasAmount(text)) fr.drawString(text, x, y, color);
        else layout(fr, text, x, y, color, true);
    }

    /** Longest start of {@code text} within {@code maxWidth}; an amount is kept whole or cut whole. */
    public static String trimToWidth(FontRenderer fr, String text, int maxWidth) {
        if (!hasAmount(text)) return fr.trimStringToWidth(text, maxWidth);
        String best = "";
        Matcher m = MillMoney.AMOUNT.matcher(text);
        int i = 0;
        while (i <= text.length()) {
            String cut = text.substring(0, i);
            if (width(fr, cut) > maxWidth) break;
            best = cut;
            if (i == text.length()) break;
            i = m.find(i) && m.start() == i ? m.end() : i + 1;
        }
        return best;
    }

    /** Word wrap that never splits an amount; "\n" breaks the line and formatting carries over. */
    public static List<String> wrap(FontRenderer fr, String text, int maxWidth) {
        if (!hasAmount(text)) return fr.listFormattedStringToWidth(text, maxWidth);
        List<String> out = new ArrayList<>();
        String carry = "";
        for (String paragraph : text.split("\n", -1)) {
            String line = carry;
            boolean empty = true;
            for (String word : paragraph.split(" ", -1)) {
                String candidate = empty ? line + word : line + " " + word;
                if (!empty && width(fr, candidate) > maxWidth) {
                    out.add(line);
                    line = FontRenderer.getFormatFromString(line) + word;
                } else {
                    line = candidate;
                }
                empty = false;
            }
            out.add(line);
            carry = FontRenderer.getFormatFromString(line);
        }
        return out;
    }

    private static boolean hasAmount(String text) {
        return text != null && MillMoney.AMOUNT.matcher(text).find();
    }

    /** Walks the text once, drawing or only measuring; returns the end x. */
    private static int layout(FontRenderer fr, String text, int x, int y, int color, boolean draw) {
        Matcher m = MillMoney.AMOUNT.matcher(text);
        String format = "";
        int last = 0;
        while (m.find()) {
            String before = format + text.substring(last, m.start());
            x = drawText(fr, before, x, y, color, draw);
            format = FontRenderer.getFormatFromString(before);
            x = layoutCoins(fr, Integer.parseInt(m.group(1)), format, x, y, color, draw);
            last = m.end();
        }
        return drawText(fr, format + text.substring(last), x, y, color, draw);
    }

    private static int layoutCoins(FontRenderer fr, int deniers, String format, int x, int y, int color, boolean draw) {
        int[] counts = MillMoney.split(deniers);
        Item[] coins = MillMoney.coinItems();
        boolean first = true;
        for (int i = 0; i < counts.length; i++) {
            // Zero shows as "0" bronze; otherwise only the coins actually present.
            if (counts[i] == 0 && !(deniers <= 0 && i == counts.length - 1)) continue;
            if (!first) x += COIN_GAP;
            first = false;
            x = drawText(fr, format + counts[i], x, y, color, draw) + COUNT_GAP;
            if (draw) drawIcon(coins[i], x, y, color);
            x += ICON;
        }
        return x;
    }

    private static int drawText(FontRenderer fr, String s, int x, int y, int color, boolean draw) {
        if (s.isEmpty()) return x;
        if (draw) fr.drawString(s, x, y, color);
        return x + fr.getStringWidth(s);
    }

    private static void drawIcon(Item coin, int x, int y, int color) {
        Minecraft mc = Minecraft.getMinecraft();
        TextureAtlasSprite sprite = mc.getRenderItem().getItemModelMesher().getParticleIcon(coin);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        // Same rule as FontRenderer: an alpha byte of (almost) zero means opaque.
        float alpha = (color & 0xFC000000) == 0 ? 1f : (color >>> 24) / 255f;
        GlStateManager.color(1f, 1f, 1f, alpha);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(x, y + ICON, 0).tex(sprite.getMinU(), sprite.getMaxV()).endVertex();
        buf.pos(x + ICON, y + ICON, 0).tex(sprite.getMaxU(), sprite.getMaxV()).endVertex();
        buf.pos(x + ICON, y, 0).tex(sprite.getMaxU(), sprite.getMinV()).endVertex();
        buf.pos(x, y, 0).tex(sprite.getMinU(), sprite.getMinV()).endVertex();
        tess.draw();
    }
}
