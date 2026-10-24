package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record DeathPointPayload(String player, String dimension, int x, int y, int z) implements CustomPacketPayload {
	public static final Type<DeathPointPayload> TYPE = new Type<>(CoopCraft.id("death_point"));

	public static final StreamCodec<RegistryFriendlyByteBuf, DeathPointPayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.player());
			buf.writeUtf(payload.dimension());
			buf.writeVarInt(payload.x());
			buf.writeVarInt(payload.y());
			buf.writeVarInt(payload.z());
		},
		buf -> new DeathPointPayload(
			buf.readUtf(),
			buf.readUtf(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt()
		)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
