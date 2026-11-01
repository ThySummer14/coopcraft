package com.thysummer.coopcraft.client;

import java.util.List;

import com.thysummer.coopcraft.net.WaypointAddPayload;
import com.thysummer.coopcraft.net.WaypointListPayload;
import com.thysummer.coopcraft.net.WaypointRemovePayload;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;

/**
 * 客户端侧的路径点：按键调出列表、按键新建，聊天栏里点击复制坐标。
 *
 * <p>列表以服务端为准，这里只缓存一份用于显示。
 */
public final class WaypointClient {
	private static List<WaypointListPayload.Entry> entries = List.of();
	private static KeyMapping listKey;
	private static KeyMapping addKey;

	private WaypointClient() {
	}

	public static void register() {
		listKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.coopcraft.waypoints",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_M,
			"category.coopcraft"
		));

		addKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.coopcraft.add_waypoint",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_N,
			"category.coopcraft"
		));

		ClientPlayNetworking.registerGlobalReceiver(WaypointListPayload.TYPE, (payload, context) ->
			context.client().execute(() -> entries = payload.entries())
		);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (listKey.consumeClick()) {
				if (client.screen == null) {
					printList(client);
				}
			}
			while (addKey.consumeClick()) {
				if (client.screen == null && client.player != null
					&& ClientPlayNetworking.canSend(WaypointAddPayload.TYPE)) {
					client.setScreen(new WaypointNameScreen());
				}
			}
		});
	}

	/** 新建界面确认后由 {@link WaypointNameScreen} 调用。 */
	static void requestAdd(String name) {
		if (ClientPlayNetworking.canSend(WaypointAddPayload.TYPE)) {
			ClientPlayNetworking.send(new WaypointAddPayload(name));
		}
	}

	static void requestRemove(int id) {
		if (ClientPlayNetworking.canSend(WaypointRemovePayload.TYPE)) {
			ClientPlayNetworking.send(new WaypointRemovePayload(id));
		}
	}

	private static void printList(Minecraft client) {
		if (client.gui == null) {
			return;
		}
		if (entries.isEmpty()) {
			client.gui.getChat().addMessage(Component.translatable("coopcraft.waypoint.empty")
				.withStyle(ChatFormatting.GRAY));
			return;
		}

		client.gui.getChat().addMessage(Component.translatable("coopcraft.waypoint.list_header", entries.size())
			.withStyle(ChatFormatting.GOLD));

		for (WaypointListPayload.Entry entry : entries) {
			Component coords = Component.literal(entry.x() + ", " + entry.y() + ", " + entry.z())
				.withStyle(style -> style
					.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,
						entry.x() + " " + entry.y() + " " + entry.z()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.translatable("coopcraft.coords.copy")))
				);

			Component remove = Component.translatable("coopcraft.waypoint.remove_button")
				.withStyle(style -> style
					.withColor(ChatFormatting.DARK_RED)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
						"/coop waypoint remove " + entry.name()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.translatable("coopcraft.waypoint.remove_hover", entry.name())))
				);

			Component line = Component.translatable(
				"coopcraft.waypoint.list_entry",
				Component.literal(entry.name()).withStyle(ChatFormatting.WHITE),
				LocationChat.dimensionName(entry.dimension()),
				coords,
				Component.literal(distanceLabel(client, entry)).withStyle(ChatFormatting.DARK_GRAY),
				remove
			).withStyle(ChatFormatting.GRAY);

			client.gui.getChat().addMessage(line);
		}
	}

	private static String distanceLabel(Minecraft client, WaypointListPayload.Entry entry) {
		if (client.player == null) {
			return "";
		}
		String dimension = client.player.level().dimension().location().toString();
		if (!dimension.equals(entry.dimension())) {
			return "·";
		}
		Vec3 pos = client.player.position();
		double dx = entry.x() + 0.5 - pos.x;
		double dy = entry.y() + 0.5 - pos.y;
		double dz = entry.z() + 0.5 - pos.z;
		return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
	}
}
