package com.thysummer.coopcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 第一人称手臂。
 *
 * <p>原版 {@code renderPlayerArm} 画的是玩家皮肤的整条手臂——一条 4×12×4 的方块，
 * 手掌和手指都不存在。近景看就是一块悬在剑旁边的色块，怎么调参数都不像手。
 *
 * <p>这里换成自制网格：小臂 + 袖口 + 手腕 + 拳头 + 蜷起的四指 + 拇指。
 * 网格原点就定在<b>握持点</b>（剑柄穿过拳心的那一处），于是武器和手臂共用一个原点，
 * 对齐不再是靠几个魔法常量去凑，而是从几何上就成立。
 */
public final class ViewmodelArm {
	private static final ResourceLocation MESH =
		ResourceLocation.fromNamespaceAndPath("coopcraft", "weapon/arm.json");

	/** 顶点色乘上去用的纯白底图，这样肤色不需要单独做贴图。 */
	private static final ResourceLocation WHITE =
		ResourceLocation.fromNamespaceAndPath("coopcraft", "textures/entity/white.png");

	private ViewmodelArm() {
	}

	/**
	 * 画一只手。调用前 poseStack 的原点必须已经落在握持点上，
	 * 且单位是方块（网格本身是像素单位）。
	 */
	public static void render(
		PoseStack poseStack,
		MultiBufferSource.BufferSource bufferSource,
		int light,
		boolean mirror
	) {
		WeaponMesh mesh = WeaponMesh.get(MESH);
		if (mesh == null) {
			return;
		}

		VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(WHITE));

		poseStack.pushPose();
		// 单位像素 → 方块
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		if (mirror) {
			poseStack.scale(-1.0F, 1.0F, 1.0F);
		}
		mesh.renderRaw(poseStack.last(), buffer, light);
		poseStack.popPose();
	}
}
