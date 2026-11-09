package com.thysummer.coopcraft.client;

import com.thysummer.coopcraft.civilization.SettlementFoodAge;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Explain the conservative cargo boundary without guessing a server-only settlement clock. */
public final class SettlementFoodTooltips {
	private SettlementFoodTooltips() { }

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			if (SettlementFoodAge.isMarked(stack)) {
				lines.add(Component.translatable("coopcraft.food_age.cargo_only").withStyle(ChatFormatting.GOLD));
				lines.add(Component.translatable("coopcraft.food_age.return_to_source").withStyle(ChatFormatting.GRAY));
			}
		});
	}
}
