package com.thysummer.coopcraft.net;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 把一个完整的进度（advancement）发给队友的客户端，让它弹出和原版一样的 toast。
 *
 * <p>直接复用 {@link AdvancementHolder#STREAM_CODEC}：原版的
 * {@code ClientboundUpdateAdvancementsPacket} 本来就是这么传进度的，
 * 所以这个编码方式在客户端一定能被正确还原，不需要自己拼字段。
 */
public record AdvancementToastPayload(AdvancementHolder advancement) implements CustomPacketPayload {
	public static final Type<AdvancementToastPayload> TYPE = new Type<>(CoopCraft.id("advancement_toast"));

	public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementToastPayload> STREAM_CODEC = AdvancementHolder.STREAM_CODEC
		.map(AdvancementToastPayload::new, AdvancementToastPayload::advancement);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
