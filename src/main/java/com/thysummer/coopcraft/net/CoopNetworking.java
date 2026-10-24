package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 进度播报的网络层：注册 payload，并把队友达成的进度转发给其他玩家。
 */
public final class CoopNetworking {
	private CoopNetworking() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.playS2C().register(AdvancementToastPayload.TYPE, AdvancementToastPayload.STREAM_CODEC);
	}

	/**
	 * 把 {@code earner} 刚达成的进度作为 toast 推送给同一服务器的其他玩家。
	 *
	 * <p>原版 {@code PlayerAdvancements#award} 在进度刚完成的瞬间已经会往聊天栏
	 * 广播一条公告（受 announceAdvancements 游戏规则控制），所以这里只补上原版
	 * 没有的那一半：让队友屏幕上真正弹出 toast。达成者本人不重复发送——他本来
	 * 就会收到原版 toast。
	 */
	public static void broadcastAdvancementToast(ServerPlayer earner, AdvancementHolder holder) {
		if (!shouldBroadcast(holder)) {
			return;
		}

		MinecraftServer server = earner.level().getServer();
		if (server == null) {
			return;
		}

		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.size() <= 1) {
			// 单人游戏没有队友，不做任何事。
			return;
		}

		AdvancementToastPayload payload = new AdvancementToastPayload(holder);
		int receivers = 0;

		for (ServerPlayer player : players) {
			if (player == earner) {
				continue;
			}

			ServerPlayNetworking.send(player, payload);
			receivers++;
		}

		CoopCraft.LOGGER.debug("Broadcast advancement toast {} to {} player(s)", holder.id(), receivers);
	}

	/**
	 * 过滤掉不该打扰别人的进度。
	 *
	 * <p>配方解锁在数据包里同样是 advancement（{@code minecraft:recipes/...}），原版
	 * 默认不公告它们；如果不排除，玩家每捡到一样新物品都会让全服弹一次 toast。
	 */
	private static boolean shouldBroadcast(AdvancementHolder holder) {
		if (holder.id().getPath().startsWith("recipes/")) {
			return false;
		}

		DisplayInfo display = holder.value().display().orElse(null);
		if (display == null) {
			// 没有 display 的进度（纯逻辑用的隐藏进度）原版也不弹 toast。
			return false;
		}

		return display.shouldShowToast() && !display.isHidden();
	}
}
