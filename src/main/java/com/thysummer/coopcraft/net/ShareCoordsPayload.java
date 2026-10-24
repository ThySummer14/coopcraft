package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ShareCoordsPayload() implements CustomPacketPayload {
	public static final Type<ShareCoordsPayload> TYPE = new Type<>(CoopCraft.id("share_coords"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShareCoordsPayload> STREAM_CODEC =
		StreamCodec.unit(new ShareCoordsPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
