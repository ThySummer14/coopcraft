package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CoordsSharedPayload(String sender, String dimension, int x, int y, int z) implements CustomPacketPayload {
	public static final Type<CoordsSharedPayload> TYPE = new Type<>(CoopCraft.id("coords_shared"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CoordsSharedPayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.sender());
			buf.writeUtf(payload.dimension());
			buf.writeVarInt(payload.x());
			buf.writeVarInt(payload.y());
			buf.writeVarInt(payload.z());
		},
		buf -> new CoordsSharedPayload(
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
