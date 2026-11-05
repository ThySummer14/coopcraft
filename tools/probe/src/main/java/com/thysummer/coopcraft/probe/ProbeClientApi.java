package com.thysummer.coopcraft.probe;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.thysummer.coopcraft.probe.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.platform.InputConstants;

public final class ProbeClientApi {
	private ProbeClientApi() {
	}

	public static Map<String, ProbeHttp.Handler> routes() {
		return Map.ofEntries(
			Map.entry("/status", body -> onClient(ProbeClientApi::status)),
			Map.entry("/ui/snapshot", body -> onClient(ProbeClientApi::uiSnapshot)),
			Map.entry("/ui/click", body -> onClient(() -> uiClick(body))),
			Map.entry("/ui/close", body -> onClient(() -> {
				Minecraft.getInstance().setScreen(null);
				return ProbeJson.ok();
			})),
			Map.entry("/ui/reset", body -> onClient(() -> {
				ProbeTextCapture.clear();
				return ProbeJson.ok();
			})),
			Map.entry("/key", body -> onClient(() -> key(body))),
			Map.entry("/command", body -> onClient(() -> command(body))),
			Map.entry("/connect", body -> onClient(() -> connect(body))),
			Map.entry("/player/use", body -> onClient(() -> playerUse(body))),
			Map.entry("/player/use-entity", body -> onClientAsync(future -> playerUseEntity(body, future))),
			Map.entry("/screenshot", body -> onClient(() -> screenshot(body))),
			Map.entry("/quit", body -> {
				Minecraft client = Minecraft.getInstance();
				if (client != null) {
					client.execute(client::stop);
				}
				return ProbeJson.ok();
			})
		);
	}

	private static JsonObject onClient(Supplier<JsonObject> action) throws Exception {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return ProbeJson.error("client not initialized");
		}
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable error) {
				future.completeExceptionally(error);
			}
		});
		return future.get(5, TimeUnit.SECONDS);
	}

	private static JsonObject onClientAsync(java.util.function.Consumer<CompletableFuture<JsonObject>> action) throws Exception {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return ProbeJson.error("client not initialized");
		}
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				action.accept(future);
			} catch (Throwable error) {
				future.completeExceptionally(error);
			}
		});
		return future.get(5, TimeUnit.SECONDS);
	}

	private static JsonObject status() {
		Minecraft client = Minecraft.getInstance();
		JsonObject json = ProbeJson.ok();
		json.addProperty("ready", client.screen != null || client.player != null);
		json.addProperty("screen", client.screen == null ? "" : client.screen.getClass().getName());
		json.addProperty("title", client.screen == null ? "" : client.screen.getTitle().getString());
		json.addProperty("player", client.player == null ? "" : client.player.getName().getString());
		json.addProperty("dimension", client.level == null ? "" : client.level.dimension().location().toString());
		json.addProperty("connected", client.getConnection() != null);
		json.addProperty("fps", client.getFps());
		json.addProperty("width", client.getWindow().getWidth());
		json.addProperty("height", client.getWindow().getHeight());
		return json;
	}

	private static JsonObject uiSnapshot() {
		Minecraft client = Minecraft.getInstance();
		Screen screen = client.screen;
		JsonObject json = ProbeJson.ok();
		json.addProperty("screen", screen == null ? "" : screen.getClass().getName());
		json.addProperty("title", screen == null ? "" : screen.getTitle().getString());

		JsonArray widgets = new JsonArray();
		if (screen != null) {
			for (GuiEventListener child : screen.children()) {
				if (child instanceof AbstractWidget widget) {
					JsonObject entry = new JsonObject();
					entry.addProperty("class", widget.getClass().getSimpleName());
					entry.addProperty("x", widget.getX());
					entry.addProperty("y", widget.getY());
					entry.addProperty("width", widget.getWidth());
					entry.addProperty("height", widget.getHeight());
					entry.addProperty("message", widget.getMessage().getString());
					entry.addProperty("active", widget.active);
					entry.addProperty("visible", widget.visible);
					widgets.add(entry);
				}
			}
		}
		json.add("widgets", widgets);

		JsonArray slots = new JsonArray();
		if (screen instanceof AbstractContainerScreen<?> container) {
			for (Slot slot : container.getMenu().slots) {
				JsonObject entry = new JsonObject();
				entry.addProperty("index", slot.index);
				entry.addProperty("x", slot.x);
				entry.addProperty("y", slot.y);
				entry.addProperty("item", slot.getItem().isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
				entry.addProperty("count", slot.getItem().getCount());
				slots.add(entry);
			}
		}
		json.add("slots", slots);

		JsonArray texts = new JsonArray();
		for (String text : ProbeTextCapture.snapshot()) {
			texts.add(text);
		}
		json.add("texts", texts);
		return json;
	}

	private static JsonObject uiClick(JsonObject body) {
		Minecraft client = Minecraft.getInstance();
		Screen screen = client.screen;
		if (screen == null) {
			return ProbeJson.error("no screen open");
		}
		int button = body.has("button") ? body.get("button").getAsInt() : 0;
		boolean shift = body.has("shift") && body.get("shift").getAsBoolean();
		if (body.has("slot")) {
			if (!(screen instanceof AbstractContainerScreen<?> container)) {
				return ProbeJson.error("current screen has no container slots");
			}
			int index = body.get("slot").getAsInt();
			if (index < 0 || index >= container.getMenu().slots.size()) {
				return ProbeJson.error("slot index out of range: " + index);
			}
			Slot slot = container.getMenu().slots.get(index);
			((AbstractContainerScreenAccessor) screen).probe$slotClicked(
				slot, slot.index, button, shift ? ClickType.QUICK_MOVE : ClickType.PICKUP
			);
		} else {
			if (!body.has("x") || !body.has("y")) {
				return ProbeJson.error("ui/click requires slot or x/y");
			}
			screen.mouseClicked(body.get("x").getAsDouble(), body.get("y").getAsDouble(), button);
		}
		JsonObject json = ProbeJson.ok();
		json.addProperty("screen", screen.getClass().getName());
		return json;
	}

	private static JsonObject key(JsonObject body) {
		Minecraft client = Minecraft.getInstance();
		String mappingName = body.has("mapping") ? body.get("mapping").getAsString() : "";
		String keyName = body.has("key") ? body.get("key").getAsString() : "";
		InputConstants.Key key = null;
		if (!keyName.isBlank()) {
			String normalized = keyName.length() == 1
				? "key.keyboard." + keyName.toLowerCase(Locale.ROOT)
				: keyName;
			try {
				key = InputConstants.getKey(normalized);
			} catch (RuntimeException error) {
				return ProbeJson.error("unknown key: " + keyName);
			}
		}
		for (KeyMapping mapping : client.options.keyMappings) {
			if ((!mappingName.isBlank() && mapping.getName().equals(mappingName))
				|| (key != null && mapping.getDefaultKey().equals(key))) {
				KeyMapping.click(mapping.getDefaultKey());
				JsonObject json = ProbeJson.ok();
				json.addProperty("mapping", mapping.getName());
				return json;
			}
		}
		return ProbeJson.error("no key mapping for " + (mappingName.isBlank() ? keyName : mappingName));
	}

	private static JsonObject command(JsonObject body) {
		Minecraft client = Minecraft.getInstance();
		String command = body.has("cmd") ? body.get("cmd").getAsString()
			: body.has("command") ? body.get("command").getAsString() : "";
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		if (command.isBlank()) {
			return ProbeJson.error("empty command");
		}
		if (client.getConnection() == null) {
			return ProbeJson.error("not connected to a server");
		}
		client.getConnection().sendCommand(command);
		JsonObject json = ProbeJson.ok();
		json.addProperty("command", command);
		return json;
	}

	private static JsonObject connect(JsonObject body) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() != null) {
			return ProbeJson.error("already connected");
		}
		String host = body.has("host") ? body.get("host").getAsString() : "127.0.0.1";
		int port = body.has("port") ? body.get("port").getAsInt() : 25565;
		ServerAddress address = new ServerAddress(host, port);
		ServerData data = new ServerData("CoopCraft Probe", address.toString(), ServerData.Type.OTHER);
		ConnectScreen.startConnecting(new TitleScreen(), client, address, data, false, null);
		JsonObject json = ProbeJson.ok();
		json.addProperty("address", address.toString());
		return json;
	}

	private static JsonObject playerUse(JsonObject body) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gameMode == null) {
			return ProbeJson.error("player not ready");
		}
		if (!body.has("x") || !body.has("y") || !body.has("z")) {
			return ProbeJson.error("player/use requires x/y/z");
		}
		BlockPos pos = BlockPos.containing(body.get("x").getAsDouble(), body.get("y").getAsDouble(), body.get("z").getAsDouble());
		Direction face = body.has("face") ? Direction.byName(body.get("face").getAsString()) : Direction.UP;
		if (face == null) {
			return ProbeJson.error("invalid face");
		}
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
		boolean shift = body.has("shift") && body.get("shift").getAsBoolean();
		InteractionResult result = interactWithShift(client, shift,
			() -> client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit));
		JsonObject json = ProbeJson.ok();
		json.addProperty("result", result.toString());
		return json;
	}

	private static void playerUseEntity(JsonObject body, CompletableFuture<JsonObject> future) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gameMode == null || client.level == null) {
			future.complete(ProbeJson.error("player not ready"));
			return;
		}
		String requestedId = body.has("uuid") ? body.get("uuid").getAsString() : "";
		java.util.UUID entityId;
		try {
			entityId = java.util.UUID.fromString(requestedId);
		} catch (IllegalArgumentException error) {
			future.complete(ProbeJson.error("player/use-entity requires a valid uuid"));
			return;
		}
		net.minecraft.world.entity.Entity target = null;
		for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
			if (entity.getUUID().equals(entityId)) {
				target = entity;
				break;
			}
		}
		if (target == null) {
			future.complete(ProbeJson.error("entity is not loaded on the client: " + entityId));
			return;
		}
		net.minecraft.world.entity.Entity interacted = target;
		boolean shift = body.has("shift") && body.get("shift").getAsBoolean();
		boolean previousShift = client.player.isShiftKeyDown();
		if (shift != previousShift) {
			client.player.setShiftKeyDown(shift);
			if (client.getConnection() != null) {
				client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,
					shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
			}
		}
		client.execute(() -> {
			try {
				if (client.player == null || client.gameMode == null || client.getConnection() == null) {
					future.complete(ProbeJson.error("player disconnected before entity interaction"));
					return;
				}
				client.player.setShiftKeyDown(shift);
				client.getConnection().send(net.minecraft.network.protocol.game.ServerboundInteractPacket.createInteractionPacket(
					interacted, shift, InteractionHand.MAIN_HAND
				));
				JsonObject json = ProbeJson.ok();
				json.addProperty("result", InteractionResult.SUCCESS.toString());
				json.addProperty("uuid", entityId.toString());
				future.complete(json);
			} catch (Throwable error) {
				future.completeExceptionally(error);
			} finally {
				if (shift != previousShift) {
					client.execute(() -> {
						if (client.player == null) {
							return;
						}
						client.player.setShiftKeyDown(previousShift);
						if (client.getConnection() != null) {
							client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,
								previousShift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
									: ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
						}
					});
				}
			}
		});
	}

	private static InteractionResult interactWithShift(Minecraft client, boolean shift, Supplier<InteractionResult> interaction) {
		boolean previousShift = client.player.isShiftKeyDown();
		if (shift == previousShift) {
			return interaction.get();
		}
		client.player.setShiftKeyDown(shift);
		if (client.getConnection() != null) {
			client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,
				shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
		}
		try {
			return interaction.get();
		} finally {
			client.execute(() -> {
				if (client.player == null) {
					return;
				}
				client.player.setShiftKeyDown(previousShift);
				if (client.getConnection() != null) {
					client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,
						previousShift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
							: ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
				}
			});
		}
	}

	private static JsonObject screenshot(JsonObject body) {
		Minecraft client = Minecraft.getInstance();
		String name = body.has("name") ? body.get("name").getAsString() : "";
		name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (name.isBlank()) {
			name = "probe-" + System.currentTimeMillis();
		}
		if (!name.endsWith(".png")) {
			name += ".png";
		}
		Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), component -> {
		});
		File file = new File(new File(client.gameDirectory, "screenshots"), name);
		JsonObject json = ProbeJson.ok();
		json.addProperty("name", name);
		json.addProperty("path", file.getAbsolutePath());
		return json;
	}
}
