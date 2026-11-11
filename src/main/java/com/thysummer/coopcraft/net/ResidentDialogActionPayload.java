package com.thysummer.coopcraft.net;

import java.util.Objects;
import java.util.UUID;
import com.thysummer.coopcraft.CoopCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** An intent only: the server resolves resident, hand, item and amount. */
public record ResidentDialogActionPayload(UUID sessionId, long requestId, Action action) implements CustomPacketPayload {
    public enum Action { REFRESH, GIFT, INVITE, CANCEL_INVITE, CLOSE, HOLD_MEETING }
    public static final Type<ResidentDialogActionPayload> TYPE = new Type<>(CoopCraft.id("resident_dialog_action_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentDialogActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, value) -> { buf.writeUUID(value.sessionId); buf.writeVarLong(value.requestId); buf.writeVarInt(value.action.ordinal()); },
        buf -> new ResidentDialogActionPayload(buf.readUUID(), buf.readVarLong(), readAction(buf)));
    public ResidentDialogActionPayload {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(action);
        if (requestId <= 0) throw new IllegalArgumentException("Request ID must be positive");
    }
    private static Action readAction(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= Action.values().length) throw new IllegalArgumentException("Invalid dialog action");
        return Action.values()[ordinal];
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
