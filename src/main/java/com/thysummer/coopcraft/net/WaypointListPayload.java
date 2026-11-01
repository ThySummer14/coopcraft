package com.thysummer.coopcraft.net;

import java.util.ArrayList;
import java.util.List;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端向客户端同步整份路径点列表。
 *
 * <p>路径点是全队共享的，所以每次增删都整份重发，不做增量同步 —— 列表最多两百条，
 * 一条消息也只有几 KB，换来的是永远不会出现两边列表不一致。
 */
public record WaypointListPayload(List<Entry> entries) implements CustomPacketPayload {
	public static final Type<WaypointListPayload> TYPE = new Type<>(CoopCraft.id("waypoint_list"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WaypointListPayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeVarInt(payload.entries().size());
			for (Entry entry : payload.entries()) {
				buf.writeVarInt(entry.id());
				buf.writeUtf(entry.name());
				buf.writeUtf(entry.dimension());
				buf.writeVarInt(entry.x());
				buf.writeVarInt(entry.y());
				buf.writeVarInt(entry.z());
				buf.writeUtf(entry.owner());
			}
		},
		buf -> {
			int size = buf.readVarInt();
			List<Entry> entries = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				entries.add(new Entry(
					buf.readVarInt(),
					buf.readUtf(),
					buf.readUtf(),
					buf.readVarInt(),
					buf.readVarInt(),
					buf.readVarInt(),
					buf.readUtf()
				));
			}
			return new WaypointListPayload(List.copyOf(entries));
		}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public record Entry(int id, String name, String dimension, int x, int y, int z, String owner) {
	}
}
