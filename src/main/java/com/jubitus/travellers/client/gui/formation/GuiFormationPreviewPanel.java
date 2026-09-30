package com.jubitus.travellers.client.gui.formation;

import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationPreviewLayout;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2-D radar preview of the current formation.
 *
 * All content (grid, axes, arrow, worker dots) is strictly clipped to the panel
 * rectangle via GL scissor so nothing ever bleeds outside the border.
 */
public class GuiFormationPreviewPanel {

    private final Minecraft mc;
    private final int w;
    private final int h;

    private float zoom = 2.2F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 6.0F;

    private float panX = 0.0F;
    private float panY = 0.0F;

    private boolean dragging = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private float dragStartPanX;
    private float dragStartPanY;

    private WorkerFormationSettings settings = WorkerFormationSettings.defaults();
    private int followerCount = 12;

    private final List<RenderPoint> renderPoints = new ArrayList<>();
    private RenderPoint hoveredPoint = null;

    public GuiFormationPreviewPanel(Minecraft mc, int width, int height) {
        this.mc = mc;
        this.w = width;
        this.h = height;
    }

    public void setData(WorkerFormationSettings settings, int followerCount) {
        this.settings = settings == null ? WorkerFormationSettings.defaults() : settings.copy().clamp();
        this.followerCount = Math.max(1, followerCount <= 0 ? 12 : followerCount);
        rebuildPoints();
    }

    public void resetView() {
        zoom = 1.8F;
        panX = 0.0F;
        panY = 0.0F;
    }

    public void zoomIn() {
        zoom = Math.min(MAX_ZOOM, zoom + 0.30F);
    }

    public void zoomOut() {
        zoom = Math.max(MIN_ZOOM, zoom - 0.30F);
    }

    public void handleWheel(int wheel) {
        if (wheel > 0) zoomIn();
        else if (wheel < 0) zoomOut();
    }

    public boolean isMouseOver(int mouseX, int mouseY, int panelX, int panelY) {
        return mouseX >= panelX && mouseX < panelX + w
                && mouseY >= panelY && mouseY < panelY + h;
    }

    public boolean mousePressed(int mouseX, int mouseY, int button, int panelX, int panelY) {
        if (!isMouseOver(mouseX, mouseY, panelX, panelY)) return false;
        if (button == 1) {
            dragging = true;
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            dragStartPanX = panX;
            dragStartPanY = panY;
            return true;
        }
        return false;
    }

    public void mouseDragged(int mouseX, int mouseY) {
        if (!dragging) return;
        panX = dragStartPanX + (mouseX - dragStartMouseX);
        panY = dragStartPanY + (mouseY - dragStartMouseY);
    }

    public void mouseReleased(int button) {
        if (button == 1) dragging = false;
    }

    public List<String> getHoverLines(int mouseX, int mouseY, int panelX, int panelY) {
        updateHover(mouseX, mouseY, panelX, panelY);
        if (hoveredPoint == null) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        if (hoveredPoint.kind == PointKind.LEADER) {
            out.add(I18n.format("travellers.worker.formation.hover.leader"));
        } else {
            out.add(I18n.format("travellers.worker.formation.hover.slot", hoveredPoint.slotIndex + 1));
            out.add(I18n.format("travellers.worker.formation.hover.offset",
                    hoveredPoint.layoutX, hoveredPoint.layoutZ));
        }
        return out;
    }

    public void draw(int panelX, int panelY, int mouseX, int mouseY) {
        drawBackground(panelX, panelY);

        beginScissor(panelX + 1, panelY + 1, w - 2, h - 2);

        drawGrid(panelX, panelY);
        drawAxes(panelX, panelY);
        drawPoints(panelX, panelY, mouseX, mouseY);

        endScissor();
    }

    private void drawBackground(int panelX, int panelY) {
        Gui.drawRect(panelX, panelY, panelX + w, panelY + h, 0xFF0D0D0D);
        Gui.drawRect(panelX + 1, panelY + 1, panelX + w - 1, panelY + h - 1, 0xFF1A1A1A);

        Gui.drawRect(panelX, panelY, panelX + w, panelY + 1, 0xFF4A4A4A);
        Gui.drawRect(panelX, panelY + h - 1, panelX + w, panelY + h, 0xFF4A4A4A);
        Gui.drawRect(panelX, panelY, panelX + 1, panelY + h, 0xFF4A4A4A);
        Gui.drawRect(panelX + w - 1, panelY, panelX + w, panelY + h, 0xFF4A4A4A);
    }

    private void drawGrid(int panelX, int panelY) {
        int step = Math.max(12, Math.round(10.0F * zoom));
        int cx = getCenterX(panelX);
        int cy = getCenterY(panelY);

        int startX = panelX + 1 + ((cx - panelX - 1) % step + step) % step;
        for (int sx = startX; sx < panelX + w - 1; sx += step) {
            Gui.drawRect(sx, panelY + 1, sx + 1, panelY + h - 1, 0xFF232323);
        }

        int startY = panelY + 1 + ((cy - panelY - 1) % step + step) % step;
        for (int sy = startY; sy < panelY + h - 1; sy += step) {
            Gui.drawRect(panelX + 1, sy, panelX + w - 1, sy + 1, 0xFF232323);
        }
    }

    private void drawAxes(int panelX, int panelY) {
        int cx = getCenterX(panelX);
        int cy = getCenterY(panelY);

        Gui.drawRect(panelX + 1, cy, panelX + w - 1, cy + 1, 0xFF363636);
        Gui.drawRect(cx, panelY + 1, cx + 1, panelY + h - 1, 0xFF363636);
    }

    private void drawPoints(int panelX, int panelY, int mouseX, int mouseY) {
        updateHover(mouseX, mouseY, panelX, panelY);

        for (RenderPoint p : renderPoints) {
            int sx = worldToScreenX(panelX, p.layoutX);
            int sy = worldToScreenY(panelY, p.layoutZ);

            if (sx < panelX + 1 || sx > panelX + w - 2
                    || sy < panelY + 1 || sy > panelY + h - 2) continue;

            boolean hovered = (p == hoveredPoint);
            int size;
            int color;

            switch (p.kind) {
                case LEADER:
                    size = hovered ? 4 : 3;
                    color = hovered ? 0xFFFFFFAA : 0xFFFFC84A;
                    break;
                default:
                    size = hovered ? 3 : 2;
                    color = hovered ? 0xFF9CF59C : 0xFF69D669;
                    break;
            }

            drawFilledSquare(sx, sy, size, color);
            drawOutlineSquare(sx, sy, size + 1, 0xFF000000);
        }
    }

    private void rebuildPoints() {
        renderPoints.clear();
        renderPoints.add(new RenderPoint(PointKind.LEADER, -1, 0, 0));

        int showCount = Math.min(64, Math.max(1, followerCount));
        List<WorkerFormationPreviewLayout.Point> pts =
                WorkerFormationPreviewLayout.buildPreview(settings, showCount);

        for (int i = 0; i < pts.size(); i++) {
            WorkerFormationPreviewLayout.Point p = pts.get(i);
            renderPoints.add(new RenderPoint(PointKind.FOLLOWER, i, p.x, p.z));
        }
    }

    private void updateHover(int mouseX, int mouseY, int panelX, int panelY) {
        hoveredPoint = null;
        if (!isMouseOver(mouseX, mouseY, panelX, panelY)) return;

        int bestDistSq = Integer.MAX_VALUE;
        for (RenderPoint p : renderPoints) {
            int sx = worldToScreenX(panelX, p.layoutX);
            int sy = worldToScreenY(panelY, p.layoutZ);
            int dx = mouseX - sx;
            int dy = mouseY - sy;
            int d2 = dx * dx + dy * dy;
            int hitRadius = (p.kind == PointKind.LEADER) ? 36 : 25;
            if (d2 <= hitRadius && d2 < bestDistSq) {
                bestDistSq = d2;
                hoveredPoint = p;
            }
        }
    }

    private int getCenterX(int panelX) {
        return panelX + (w / 2) + Math.round(panX);
    }

    private int getCenterY(int panelY) {
        return panelY + (h / 2) - 20 + Math.round(panY);
    }

    private int worldToScreenX(int panelX, int layoutX) {
        return getCenterX(panelX) + Math.round(layoutX * zoom);
    }

    private int worldToScreenY(int panelY, int layoutZ) {
        return getCenterY(panelY) + Math.round(layoutZ * zoom);
    }

    private void beginScissor(int x, int y, int width, int height) {
        ScaledResolution sr = new ScaledResolution(mc);
        int s = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                x * s,
                mc.displayHeight - (y + height) * s,
                width * s,
                height * s
        );
    }

    private static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private static void drawFilledSquare(int cx, int cy, int half, int argb) {
        Gui.drawRect(cx - half, cy - half, cx + half + 1, cy + half + 1, argb);
    }

    private static void drawOutlineSquare(int cx, int cy, int half, int argb) {
        Gui.drawRect(cx - half, cy - half, cx + half + 1, cy - half + 1, argb);
        Gui.drawRect(cx - half, cy + half, cx + half + 1, cy + half + 1, argb);
        Gui.drawRect(cx - half, cy - half, cx - half + 1, cy + half + 1, argb);
        Gui.drawRect(cx + half, cy - half, cx + half + 1, cy + half + 1, argb);
    }

    private enum PointKind { LEADER, FOLLOWER }

    private static final class RenderPoint {
        final PointKind kind;
        final int slotIndex;
        final int layoutX;
        final int layoutZ;

        RenderPoint(PointKind kind, int slotIndex, int layoutX, int layoutZ) {
            this.kind = kind;
            this.slotIndex = slotIndex;
            this.layoutX = layoutX;
            this.layoutZ = layoutZ;
        }
    }
}