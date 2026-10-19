package com.jubitus.travellers.unit.render;

import com.jubitus.travellers.mixins.MixinModelBoxAccessor;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * Draws a set of model parts in one buffer instead of one display list call per part.
 * Places every vertex exactly where ModelRenderer.render would (offset, rotation point, Z-Y-X rotation, children);
 * the pose itself still comes from the model's own animation code.
 */
@SideOnly(Side.CLIENT)
public final class ModelFlattener {

    // Row-major 3x4 affine matrix: rotation | translation.
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    private ModelFlattener() {
    }

    public static void draw(ModelRenderer[] parts, float scale) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL);
        for (ModelRenderer part : parts) {
            add(buffer, part, IDENTITY, scale);
        }
        tessellator.draw();
    }

    private static void add(BufferBuilder buffer, ModelRenderer part, float[] parent, float scale) {
        if (part.isHidden || !part.showModel) return;
        float[] m = parent.clone();
        translate(m, part.offsetX + part.rotationPointX * scale, part.offsetY + part.rotationPointY * scale,
                part.offsetZ + part.rotationPointZ * scale);
        if (part.rotateAngleZ != 0.0F) rotateZ(m, part.rotateAngleZ);
        if (part.rotateAngleY != 0.0F) rotateY(m, part.rotateAngleY);
        if (part.rotateAngleX != 0.0F) rotateX(m, part.rotateAngleX);

        for (ModelBox box : part.cubeList) {
            for (TexturedQuad quad : ((MixinModelBoxAccessor) box).travellers$getQuadList()) {
                addQuad(buffer, quad, m, scale);
            }
        }
        if (part.childModels != null) {
            for (ModelRenderer child : part.childModels) {
                add(buffer, child, m, scale);
            }
        }
    }

    // Same normal as TexturedQuad.draw, then rotated with the part.
    private static void addQuad(BufferBuilder buffer, TexturedQuad quad, float[] m, float scale) {
        PositionTextureVertex[] v = quad.vertexPositions;
        Vec3d a = v[1].vector3D.subtractReverse(v[0].vector3D);
        Vec3d b = v[1].vector3D.subtractReverse(v[2].vector3D);
        Vec3d n = b.crossProduct(a).normalize();
        float nx = (float) (m[0] * n.x + m[1] * n.y + m[2] * n.z);
        float ny = (float) (m[4] * n.x + m[5] * n.y + m[6] * n.z);
        float nz = (float) (m[8] * n.x + m[9] * n.y + m[10] * n.z);
        for (int i = 0; i < 4; i++) {
            Vec3d p = v[i].vector3D;
            double x = p.x * scale, y = p.y * scale, z = p.z * scale;
            buffer.pos(m[0] * x + m[1] * y + m[2] * z + m[3],
                            m[4] * x + m[5] * y + m[6] * z + m[7],
                            m[8] * x + m[9] * y + m[10] * z + m[11])
                    .tex(v[i].texturePositionX, v[i].texturePositionY)
                    .normal(nx, ny, nz)
                    .endVertex();
        }
    }

    private static void translate(float[] m, float x, float y, float z) {
        m[3] += m[0] * x + m[1] * y + m[2] * z;
        m[7] += m[4] * x + m[5] * y + m[6] * z;
        m[11] += m[8] * x + m[9] * y + m[10] * z;
    }

    // Post-multiplies by a rotation, as GlStateManager.rotate does.
    private static void rotateX(float[] m, float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        for (int r = 0; r < 3; r++) {
            float c1 = m[r * 4 + 1], c2 = m[r * 4 + 2];
            m[r * 4 + 1] = c1 * c + c2 * s;
            m[r * 4 + 2] = -c1 * s + c2 * c;
        }
    }

    private static void rotateY(float[] m, float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        for (int r = 0; r < 3; r++) {
            float c0 = m[r * 4], c2 = m[r * 4 + 2];
            m[r * 4] = c0 * c - c2 * s;
            m[r * 4 + 2] = c0 * s + c2 * c;
        }
    }

    private static void rotateZ(float[] m, float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        for (int r = 0; r < 3; r++) {
            float c0 = m[r * 4], c1 = m[r * 4 + 1];
            m[r * 4] = c0 * c + c1 * s;
            m[r * 4 + 1] = -c0 * s + c1 * c;
        }
    }
}
