package com.thysummer.coopcraft.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import com.thysummer.coopcraft.CoopCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ResidentDialogPayload(Kind kind, UUID sessionId, long revision, long acknowledgedRequest,
                                    Snapshot snapshot, Component message) implements CustomPacketPayload {
    public enum Kind { OPEN, UPDATE, CLOSE }
    public static final int MAX_LINES = 32;
    public static final int MAX_DIMENSION_LENGTH = 128;
    public static final int MAX_COMPONENT_LENGTH = 2048;
    public static final int MAX_COMPONENT_BYTES = 16384;
    public static final Type<ResidentDialogPayload> TYPE = new Type<>(CoopCraft.id("resident_dialog"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentDialogPayload> STREAM_CODEC = StreamCodec.of(
        (buf, value) -> value.write(buf), ResidentDialogPayload::read);
    public ResidentDialogPayload {
        Objects.requireNonNull(kind); Objects.requireNonNull(sessionId); message = freezeComponent(message);
        if (revision < 0 || acknowledgedRequest < 0) throw new IllegalArgumentException("Negative dialog counter");
        if ((kind == Kind.CLOSE) != (snapshot == null)) throw new IllegalArgumentException("Invalid dialog snapshot");
    }
    @Override public Component message() { return freezeComponent(message); }
    public record Snapshot(Component title, String dimension, List<Component> lines, boolean canGift,
                           boolean canInvite, boolean canCancel, Component giftHint, Component meetingHint) {
        public Snapshot {
            title = freezeComponent(title); giftHint = freezeComponent(giftHint); meetingHint = freezeComponent(meetingHint);
            Objects.requireNonNull(dimension);
            if (dimension.isEmpty() || dimension.length() > MAX_DIMENSION_LENGTH) throw new IllegalArgumentException("Invalid dimension");
            if (lines.size() > MAX_LINES) throw new IllegalArgumentException("Too many dialog lines");
            lines = lines.stream().map(ResidentDialogPayload::freezeComponent).toList();
        }
        @Override public Component title() { return freezeComponent(title); }
        @Override public Component giftHint() { return freezeComponent(giftHint); }
        @Override public Component meetingHint() { return freezeComponent(meetingHint); }
        @Override public List<Component> lines() { return lines.stream().map(ResidentDialogPayload::freezeComponent).toList(); }
    }
    private static void checkComponent(Component value) {
        Objects.requireNonNull(value);
        if (value.getString().length() > MAX_COMPONENT_LENGTH) throw new IllegalArgumentException("Dialog component too long");
    }
    private static void checkTree(Component value, int depth, int[] nodes) {
        Objects.requireNonNull(value);
        if (depth > 16 || ++nodes[0] > 128) throw new IllegalArgumentException("Dialog component tree too large");
        for (Component child : value.getSiblings()) checkTree(child, depth + 1, nodes);
        if (value.getContents() instanceof TranslatableContents translated) {
            for (Object argument : translated.getArgs()) if (argument instanceof Component child) checkTree(child, depth + 1, nodes);
        }
        var hover = value.getStyle().getHoverEvent();
        if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT)
            checkTree(hover.getValue(HoverEvent.Action.SHOW_TEXT), depth + 1, nodes);
    }
    private static Component freezeComponent(Component value) {
        checkTree(value, 0, new int[]{0});
        checkComponent(value);
        // Bound all serialized data, including styles, hover data and empty sibling nodes.
        var json = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        if (json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_COMPONENT_BYTES)
            throw new IllegalArgumentException("Dialog component encoding too large");
        Component frozen = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        var preflight = new RegistryFriendlyByteBuf(Unpooled.buffer(256, MAX_COMPONENT_BYTES), RegistryAccess.EMPTY);
        try { ComponentSerialization.STREAM_CODEC.encode(preflight, frozen); }
        finally { preflight.release(); }
        return frozen;
    }
    private static Component readComponent(RegistryFriendlyByteBuf buf) {
        var nested = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(buf.readByteArray(MAX_COMPONENT_BYTES)), buf.registryAccess());
        try {
            Component value = ComponentSerialization.STREAM_CODEC.decode(nested);
            if (nested.isReadable()) throw new IllegalArgumentException("Trailing component bytes");
            checkComponent(value);
            return value;
        } finally { nested.release(); }
    }
    private static void writeComponent(RegistryFriendlyByteBuf buf, Component value) {
        // A bounded buffer fails before allocating beyond the protocol limit.
        var nested = new RegistryFriendlyByteBuf(Unpooled.buffer(256, MAX_COMPONENT_BYTES), buf.registryAccess());
        try {
            ComponentSerialization.STREAM_CODEC.encode(nested, value);
            buf.writeVarInt(nested.readableBytes());
            buf.writeBytes(nested);
        } finally { nested.release(); }
    }
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(kind.ordinal()); buf.writeUUID(sessionId); buf.writeVarLong(revision); buf.writeVarLong(acknowledgedRequest);
        if (snapshot != null) {
            writeComponent(buf, snapshot.title);
            buf.writeUtf(snapshot.dimension, MAX_DIMENSION_LENGTH);
            buf.writeVarInt(snapshot.lines.size());
            snapshot.lines.forEach(line -> writeComponent(buf, line));
            buf.writeBoolean(snapshot.canGift); buf.writeBoolean(snapshot.canInvite); buf.writeBoolean(snapshot.canCancel);
            writeComponent(buf, snapshot.giftHint);
            writeComponent(buf, snapshot.meetingHint);
        }
        writeComponent(buf, message);
    }
    private static ResidentDialogPayload read(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= Kind.values().length) throw new IllegalArgumentException("Invalid dialog kind");
        Kind kind = Kind.values()[ordinal];
        UUID id = buf.readUUID(); long revision = buf.readVarLong(); long ack = buf.readVarLong();
        if (revision < 0 || ack < 0) throw new IllegalArgumentException("Negative dialog counter");
        Snapshot snapshot = null;
        if (kind != Kind.CLOSE) {
            Component title = readComponent(buf); String dimension = buf.readUtf(MAX_DIMENSION_LENGTH);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_LINES) throw new IllegalArgumentException("Invalid dialog line count");
            List<Component> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) lines.add(readComponent(buf));
            snapshot = new Snapshot(title, dimension, lines, buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                readComponent(buf), readComponent(buf));
        }
        return new ResidentDialogPayload(kind, id, revision, ack, snapshot, readComponent(buf));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
