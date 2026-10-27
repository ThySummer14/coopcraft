package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络层：注册 payload，并把队友互动消息转发给其他玩家。
 */
public final class CoopNetworking {
	private CoopNetworking() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.playS2C().register(AdvancementToastPayload.TYPE, AdvancementToastPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ShareCoordsPayload.TYPE, ShareCoordsPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(CoordsSharedPayload.TYPE, CoordsSharedPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(DeathPointPayload.TYPE, DeathPointPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(TeamHealthPayload.TYPE, TeamHealthPayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ShareCoordsPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();
			context.server().execute(() -> shareCoords(sender));
		});
	}

	/**
	 * 把发送者的位置广播给其他队友。
	 *
	 * <p>坐标由服务端从 {@link ServerPlayer} 读取，不信任客户端上报的值。
	 */
	private static void shareCoords(ServerPlayer sender) {
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return;
		}

		CoordsSharedPayload payload = new CoordsSharedPayload(
			sender.getScoreboardName(),
			sender.level().dimension().location().toString(),
			sender.getBlockX(),
			sender.getBlockY(),
			sender.getBlockZ()
		);

		int receivers = 0;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == sender) {
				continue;
			}

			if (!ServerPlayNetworking.canSend(player, CoordsSharedPayload.TYPE)) {
				continue;
			}

			ServerPlayNetworking.send(player, payload);
			receivers++;
		}

		if (receivers == 0) {
			sender.sendSystemMessage(Component.translatable("coopcraft.coords.none"));
		} else {
			sender.sendSystemMessage(Component.translatable("coopcraft.coords.confirm", Integer.toString(receivers)));
		}

		CoopCraft.LOGGER.debug("Shared coords of {} with {} player(s)", sender.getScoreboardName(), receivers);
	}

	/**
	 * 把全队血量推给每个玩家，供队友血量板渲染。
	 *
	 * <p>单人游戏直接跳过 —— HUD 上没有别的人可显示，没必要每秒发一个空包。
	 */
	public static void broadcastTeamHealth(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.size() <= 1) {
			return;
		}

		List<TeamHealthPayload.Entry> entries = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			entries.add(new TeamHealthPayload.Entry(
				player.getScoreboardName(),
				(int) Math.ceil(player.getHealth()),
				(int) Math.ceil(player.getMaxHealth())
			));
		}

		TeamHealthPayload payload = new TeamHealthPayload(List.copyOf(entries));

		for (ServerPlayer player : players) {
			if (ServerPlayNetworking.canSend(player, TeamHealthPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	/**
	 * 把玩家死亡的位置推送给其他队友，方便回去帮忙捡装备。
	 */
	public static void broadcastDeathPoint(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}

		DeathPointPayload payload = new DeathPointPayload(
			player.getScoreboardName(),
			player.level().dimension().location().toString(),
			player.getBlockX(),
			player.getBlockY(),
			player.getBlockZ()
		);

		int receivers = 0;

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other == player) {
				continue;
			}

			if (!ServerPlayNetworking.canSend(other, DeathPointPayload.TYPE)) {
				continue;
			}

			ServerPlayNetworking.send(other, payload);
			receivers++;
		}

		CoopCraft.LOGGER.debug("Broadcast death point of {} to {} player(s)", player.getScoreboardName(), receivers);
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
