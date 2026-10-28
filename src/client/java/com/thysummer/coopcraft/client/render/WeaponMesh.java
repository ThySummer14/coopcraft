package com.thysummer.coopcraft.client.render;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 一个可以从 JSON 加载并直接绘制的武器网格。
 *
 * <p>为什么不用原版模型体系：原版的 {@code BakedModel} 是为「一块薄板 + 显示变换」设计的，
 * 要往里塞自定义三角形得注册模型加载器、改写烘焙流程，绕一大圈。这里的数据量很小
 * （一把剑一百多个三角形），直接用 {@link VertexConsumer} 画更直接，也更好调。
 *
 * <p>顶点不共享，每个三角形独立算平面法线——这正是 Minecraft 想要的方块味，
 * 而不是光滑着色那种塑料感。
 */
public final class WeaponMesh {
	/** 已加载的网格，按资源路径缓存。 */
	private static final Map<ResourceLocation, WeaponMesh> CACHE = new HashMap<>();

	private final float[] positions;
	private final float[] uvs;
	private final int[] indices;
	/** 每个顶点三个分量，按三角形平面法线展开。 */
	private final float[] normals;
	/** 可选：每顶点 RGB。为 null 时使用纯白（贴图原色）。 */
	private final float[] colors;

	private WeaponMesh(float[] positions, float[] uvs, int[] indices, float[] colors) {
		this.positions = positions;
		this.uvs = uvs;
		this.indices = indices;
		this.colors = colors;
		this.normals = buildNormals(positions, indices);
	}

	/** 清空缓存，资源重载时调用。 */
	public static void clearCache() {
		CACHE.clear();
	}

	/**
	 * 加载网格，失败返回 null（调用方退回原版渲染）。
	 */
	public static WeaponMesh get(ResourceLocation id) {
		if (CACHE.containsKey(id)) {
			return CACHE.get(id);
		}

		WeaponMesh mesh = null;
		try {
			Resource resource = net.minecraft.client.Minecraft.getInstance()
				.getResourceManager()
				.getResource(id)
				.orElse(null);

			if (resource != null) {
				try (InputStream stream = resource.open();
					InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					mesh = new WeaponMesh(
						toFloats(json.getAsJsonArray("positions")),
						toFloats(json.getAsJsonArray("uvs")),
						toInts(json.getAsJsonArray("indices")),
						json.has("colors") ? toFloats(json.getAsJsonArray("colors")) : null
					);
				}
			} else {
				CoopCraft.LOGGER.warn("weapon mesh not found: {}", id);
			}
		} catch (Exception e) {
			CoopCraft.LOGGER.warn("failed to load weapon mesh {}", id, e);
		}

		CACHE.put(id, mesh);
		return mesh;
	}

	private static float[] toFloats(JsonArray array) {
		float[] out = new float[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsFloat();
		}
		return out;
	}

	private static int[] toInts(JsonArray array) {
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsInt();
		}
		return out;
	}

	private static float[] buildNormals(float[] positions, int[] indices) {
		float[] normals = new float[positions.length];
		for (int i = 0; i < indices.length; i += 3) {
			int a = indices[i] * 3;
			int b = indices[i + 1] * 3;
			int c = indices[i + 2] * 3;

			Vector3f ab = new Vector3f(
				positions[b] - positions[a],
				positions[b + 1] - positions[a + 1],
				positions[b + 2] - positions[a + 2]
			);
			Vector3f ac = new Vector3f(
				positions[c] - positions[a],
				positions[c + 1] - positions[a + 1],
				positions[c + 2] - positions[a + 2]
			);
			Vector3f n = ab.cross(ac);
			if (n.lengthSquared() > 1.0E-8F) {
				n.normalize();
			} else {
				n.set(0.0F, 1.0F, 0.0F);
			}

			for (int k = 0; k < 3; k++) {
				int v = indices[i + k] * 3;
				normals[v] = n.x;
				normals[v + 1] = n.y;
				normals[v + 2] = n.z;
			}
		}
		return normals;
	}

	/**
	 * 绘制整个网格。
	 *
	 * @param pose   当前 PoseStack 的位姿
	 * @param buffer 目标缓冲区
	 * @param sprite 物品贴图在图集里的位置，用来把 0..1 的 UV 映射到图集坐标
	 * @param light  打包后的光照
	 */
	public void render(PoseStack.Pose pose, VertexConsumer buffer, TextureAtlasSprite sprite, int light) {
		Matrix4f matrix = pose.pose();

		float u0 = sprite.getU0();
		float u1 = sprite.getU1();
		float v0 = sprite.getV0();
		float v1 = sprite.getV1();

		// 只画一遍，靠 RenderType 关掉背面剔除来保证任何角度都看得见。
		//
		// 试过"正反各画一遍"，结果两遍落在完全相同的深度上，z-fighting 把剑身
		// 打成一排横条纹。改用不剔除背面的 RenderType 就没这个问题。
		for (int i = 0; i < this.indices.length; i++) {
			int vi = this.indices[i];
			int p = vi * 3;

			float u = u0 + (u1 - u0) * this.uvs[vi * 2];
			float v = v0 + (v1 - v0) * this.uvs[vi * 2 + 1];

			buffer.addVertex(matrix, this.positions[p], this.positions[p + 1], this.positions[p + 2])
				.setColor(0xFFFFFFFF)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, this.normals[p], this.normals[p + 1], this.normals[p + 2]);
		}
	}

	/**
	 * 用网格自带的 UV 和顶点色绘制。
	 *
	 * <p>手臂这类"没有贴图、只有颜色"的部件走这条路：UV 直接落在一张纯白图上，
	 * 真正的颜色由顶点色决定，所以不需要给每种肤色单独做贴图。
	 */
	public void renderRaw(PoseStack.Pose pose, VertexConsumer buffer, int light) {
		Matrix4f matrix = pose.pose();
		boolean hasColor = this.colors != null && this.colors.length >= this.positions.length;

		for (int i = 0; i < this.indices.length; i++) {
			int vi = this.indices[i];
			int p = vi * 3;

			int argb = 0xFFFFFFFF;
			if (hasColor) {
				int r = clamp255(this.colors[p]);
				int g = clamp255(this.colors[p + 1]);
				int b = clamp255(this.colors[p + 2]);
				argb = 0xFF000000 | (r << 16) | (g << 8) | b;
			}

			buffer.addVertex(matrix, this.positions[p], this.positions[p + 1], this.positions[p + 2])
				.setColor(argb)
				.setUv(this.uvs[vi * 2], this.uvs[vi * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, this.normals[p], this.normals[p + 1], this.normals[p + 2]);
		}
	}

	private static int clamp255(float v) {
		int i = (int) (v * 255.0F + 0.5F);
		return i < 0 ? 0 : Math.min(i, 255);
	}
}
