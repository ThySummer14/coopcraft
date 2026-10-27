package com.thysummer.coopcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 把物品画成 3D 武器模型。
 *
 * <p>模型按工具类别共用（剑/斧/镐/锄各一套），贴图则来自物品本身，
 * 所以同一套模型能自动适配木、石、铁、金、钻石、下界合金所有材质，
 * 装的其他模组武器只要打了原版标签也一样生效。
 */
public final class WeaponRenderer {
	private static final String NAMESPACE = "coopcraft";

	private WeaponRenderer() {
	}

	/** 这个物品有没有对应的 3D 模型。 */
	public static boolean supports(ItemStack stack) {
		return meshFor(stack) != null;
	}

	/**
	 * 绘制武器。
	 *
	 * @return false 表示没有可用模型，调用方应该退回原版渲染
	 */
	public static boolean render(
		PoseStack poseStack,
		MultiBufferSource.BufferSource bufferSource,
		ItemStack stack,
		int light
	) {
		WeaponMesh mesh = meshFor(stack);
		if (mesh == null) {
			return false;
		}

		TextureAtlasSprite sprite = spriteFor(stack);
		if (sprite == null) {
			return false;
		}

		VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));

		poseStack.pushPose();
		// 模型单位是像素，转成方块
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		mesh.render(poseStack.last(), buffer, sprite, light);
		poseStack.popPose();
		return true;
	}

	/** 按工具类别选模型。 */
	private static WeaponMesh meshFor(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}

		String shape;
		if (stack.is(ItemTags.SWORDS)) {
			shape = "sword";
		} else if (stack.is(ItemTags.AXES)) {
			shape = "axe";
		} else if (stack.is(ItemTags.PICKAXES)) {
			shape = "pickaxe";
		} else if (stack.is(ItemTags.HOES)) {
			shape = "hoe";
		} else {
			return null;
		}

		return WeaponMesh.get(
			ResourceLocation.fromNamespaceAndPath(NAMESPACE, "weapon/" + shape + ".json")
		);
	}

	/** 已经打印过 sprite 的物品，避免每帧刷日志。 */
	private static final java.util.Set<net.minecraft.world.item.Item> LOGGED = new java.util.HashSet<>();

	/** 取这个物品在图集里的贴图位置。 */
	private static TextureAtlasSprite spriteFor(ItemStack stack) {
		Minecraft client = Minecraft.getInstance();
		try {
			BakedModel model = client.getItemRenderer().getModel(stack, client.level, client.player, 0);
			TextureAtlasSprite sprite = model.getParticleIcon();

			if (sprite != null && LOGGED.add(stack.getItem())) {
				CoopCraft.LOGGER.info(
					"[weapon] {} -> sprite={} u=[{},{}] v=[{},{}]",
					stack.getItem(), sprite.contents().name(),
					sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()
				);
			}
			return sprite;
		} catch (Exception e) {
			CoopCraft.LOGGER.warn("failed to resolve sprite for {}", stack, e);
			return null;
		}
	}
}
