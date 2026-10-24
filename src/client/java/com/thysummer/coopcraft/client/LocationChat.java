package com.thysummer.coopcraft.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

public final class LocationChat {
	private LocationChat() {
	}

	public static void show(Minecraft client, String messageKey, String subject, String dimensionId,
			int x, int y, int z, String copyHoverKey) {
		Component coords = Component.literal(x + ", " + y + ", " + z)
			.withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent(
					ClickEvent.Action.COPY_TO_CLIPBOARD,
					x + " " + y + " " + z
				))
				.withHoverEvent(new HoverEvent(
					HoverEvent.Action.SHOW_TEXT,
					Component.translatable(copyHoverKey)
				))
			);

		Component message = Component.translatable(
			messageKey,
			subject,
			dimensionName(dimensionId),
			coords
		).withStyle(ChatFormatting.GRAY);

		client.gui.getChat().addMessage(message);
	}

	private static Component dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> Component.translatable("coopcraft.dimension.overworld");
			case "minecraft:the_nether" -> Component.translatable("coopcraft.dimension.the_nether");
			case "minecraft:the_end" -> Component.translatable("coopcraft.dimension.the_end");
			default -> Component.literal(dimension);
		};
	}
}
