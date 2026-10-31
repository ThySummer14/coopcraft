package com.thysummer.coopcraft.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「超过 20 点的那部分血量」画成金色，让玩家一眼看出有多少血是装备/药水加上去的。
 *
 * <p>为什么是覆盖重绘、而不是给原版的心改颜色：心形贴图本身是红色的，
 * 而 {@code GuiGraphics.setColor} 是乘法混合 —— 红心乘任何颜色都还是红。
 * 实测把颜色乘成纯品红，心也只是暗了一点。所以这里自带三张金色贴图，
 * 在原版画完之后把「额外」那几行盖上去。
 *
 * <p>底行永远是 1~20 点的基础血量（20 点 = 10 颗心 = 正好一行），
 * 所以只需要补画 index &gt;= 10 的心，原版画的基础血条一个像素都不用动。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	private static final int COOPCRAFT_BASE_HEALTH = 20;
	private static final int COOPCRAFT_HEARTS_PER_ROW = 10;
	private static final int COOPCRAFT_HEART_STEP = 8;
	private static final int COOPCRAFT_HEART_SIZE = 9;

	private static final ResourceLocation COOPCRAFT_HEART_FULL =
		ResourceLocation.fromNamespaceAndPath("coopcraft", "hud/heart_extra/full");
	private static final ResourceLocation COOPCRAFT_HEART_HALF =
		ResourceLocation.fromNamespaceAndPath("coopcraft", "hud/heart_extra/half");
	private static final ResourceLocation COOPCRAFT_HEART_EMPTY =
		ResourceLocation.fromNamespaceAndPath("coopcraft", "hud/heart_extra/container");

	@Inject(method = "renderHearts", at = @At("TAIL"))
	private void coopcraft$drawExtraHealthHearts(
		GuiGraphics graphics, Player player, int x, int y, int height,
		int offsetHeartIndex, float maxHealth, int currentHealth,
		int displayHealth, int absorptionAmount, boolean renderHighlight,
		CallbackInfo ci
	) {
		int totalHealth = Mth.ceil(maxHealth);
		if (totalHealth <= COOPCRAFT_BASE_HEALTH) {
			return;
		}

		// 原版循环按「心索引」走：第 index 颗心覆盖血量 [index*2, index*2+2)。
		int totalHearts = (totalHealth + 1) / 2;
		int baseHearts = COOPCRAFT_BASE_HEALTH / 2;

		for (int index = totalHearts - 1; index >= baseHearts; index--) {
			int column = index % COOPCRAFT_HEARTS_PER_ROW;
			int row = index / COOPCRAFT_HEARTS_PER_ROW;
			int heartX = x + column * COOPCRAFT_HEART_STEP;
			int heartY = y - row * height;

			int heartHealth = currentHealth - index * 2;
			ResourceLocation sprite;
			if (heartHealth >= 2) {
				sprite = COOPCRAFT_HEART_FULL;
			} else if (heartHealth == 1) {
				sprite = COOPCRAFT_HEART_HALF;
			} else {
				sprite = COOPCRAFT_HEART_EMPTY;
			}

			graphics.blitSprite(sprite, heartX, heartY, COOPCRAFT_HEART_SIZE, COOPCRAFT_HEART_SIZE);
		}
	}
}
