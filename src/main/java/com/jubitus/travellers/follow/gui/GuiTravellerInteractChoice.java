package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.MsgStartPlayerFollow;
import com.jubitus.travellers.follow.net.MsgTalkToTraveller;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.net.TravellersNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Right-click on a traveller: a small "Talk" / "Follow him" choice. */
@SideOnly(Side.CLIENT)
public class GuiTravellerInteractChoice extends GuiScreen implements ITravellersGui {

    private static final int ID_TALK = 1;
    private static final int ID_FOLLOW = 2;
    private static final int PANEL_W = 160;
    private static final int BTN_H = 20;
    private static final int PAD = 8;

    private final int entityId;
    private int panelX, panelY, panelH;

    public GuiTravellerInteractChoice(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        panelH = PAD + this.fontRenderer.FONT_HEIGHT + PAD + BTN_H + 4 + BTN_H + PAD;
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - panelH) / 2;
        int y = panelY + PAD + this.fontRenderer.FONT_HEIGHT + PAD;
        this.buttonList.add(new GuiTravellersButton(ID_TALK, panelX + PAD, y, PANEL_W - 2 * PAD, BTN_H,
                I18n.format("travellers.follow.choice.talk")));
        this.buttonList.add(new GuiTravellersButton(ID_FOLLOW, panelX + PAD, y + BTN_H + 4, PANEL_W - 2 * PAD, BTN_H,
                I18n.format("travellers.follow.choice.follow")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_TALK) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgTalkToTraveller(entityId));
        } else if (button.id == ID_FOLLOW) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgStartPlayerFollow(entityId));
        }
        this.mc.displayGuiScreen(null);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(panelX, panelY, panelX + PANEL_W, panelY + panelH, TravellersGuiTheme.COL_PANEL);
        drawHorizontalLine(panelX, panelX + PANEL_W - 1, panelY, TravellersGuiTheme.COL_BORDER);
        drawHorizontalLine(panelX, panelX + PANEL_W - 1, panelY + panelH - 1, TravellersGuiTheme.COL_BORDER);
        drawVerticalLine(panelX, panelY, panelY + panelH - 1, TravellersGuiTheme.COL_BORDER);
        drawVerticalLine(panelX + PANEL_W - 1, panelY, panelY + panelH - 1, TravellersGuiTheme.COL_BORDER);

        Entity e = this.mc.world != null ? this.mc.world.getEntityByID(entityId) : null;
        String title = e != null ? e.getName() : "";
        this.fontRenderer.drawString(title, panelX + (PANEL_W - this.fontRenderer.getStringWidth(title)) / 2,
                panelY + PAD, TravellersGuiTheme.COL_TEXT_HOV);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
