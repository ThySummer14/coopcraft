package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 全队血量快照。服务端每秒推一次，客户端只拿来画 HUD。
 *
 * <p>血量用整数点数传（原版是 float，但半颗心的精度对 HUD 够用，少 4 字节/人）。
 */
public record TeamHealthPayload(List<Entry> entries) implements CustomPacketPayload {
	/** 单个队友的血量状态。 */
	public record Entry(String name, int health, int maxHealth) {
	}

	public static final Type<TeamHealthPayload> TYPE = new Type<>(CoopCraft.id("team_health"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TeamHealthPayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeVarInt(payload.entries().size());
			for (Entry entry : payload.entries()) {
				buf.writeUtf(entry.name());
				buf.writeVarInt(entry.health());
				buf.writeVarInt(entry.maxHealth());
			}
		},
		buf -> {
			int size = buf.readVarInt();
			List<Entry> entries = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				entries.add(new Entry(buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
			}
			return new TeamHealthPayload(List.copyOf(entries));
		}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
