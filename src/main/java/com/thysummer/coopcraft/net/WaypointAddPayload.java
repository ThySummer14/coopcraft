package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端请求在当前位置新增一个路径点。
 *
 * <p>只传名字，坐标由服务端从玩家身上读 —— 客户端报上来的坐标不作数。
 */
public record WaypointAddPayload(String name) implements CustomPacketPayload {
	public static final Type<WaypointAddPayload> TYPE = new Type<>(CoopCraft.id("waypoint_add"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WaypointAddPayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeUtf(payload.name()),
		buf -> new WaypointAddPayload(buf.readUtf())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
