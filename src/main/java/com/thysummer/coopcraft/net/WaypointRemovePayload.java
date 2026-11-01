package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端请求删除一个路径点。
 *
 * <p>谁能删谁不能删由服务端判定，客户端只负责把 id 递上来。
 */
public record WaypointRemovePayload(int id) implements CustomPacketPayload {
	public static final Type<WaypointRemovePayload> TYPE = new Type<>(CoopCraft.id("waypoint_remove"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WaypointRemovePayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeVarInt(payload.id()),
		buf -> new WaypointRemovePayload(buf.readVarInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
