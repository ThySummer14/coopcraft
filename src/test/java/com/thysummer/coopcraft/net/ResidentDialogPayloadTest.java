package com.thysummer.coopcraft.net;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.junit.jupiter.api.Assertions.*;

class ResidentDialogPayloadTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    private ResidentDialogPayload.Snapshot snapshot() {
        return new ResidentDialogPayload.Snapshot(Component.translatable("entity.minecraft.villager"), "minecraft:overworld",
            List.of(Component.translatable("coopcraft.resident_dialog.result", Component.literal("Ada"))), true, false, true, true,
            Component.literal("Food"), Component.literal("Invitation is not arrival"),
            Component.literal("Proposal: daily food cap\nArrived: 3; Cost: 1 social hour per participant"));
    }
    @Test void allKindsAndActionsRoundTripWithLocalizedComponents() {
        for (var kind : ResidentDialogPayload.Kind.values()) {
            var expected = new ResidentDialogPayload(kind, UUID.randomUUID(), 5, 3,
                kind == ResidentDialogPayload.Kind.CLOSE ? null : snapshot(), Component.translatable("gui.done"));
            var buf = buffer();
            try {
                ResidentDialogPayload.STREAM_CODEC.encode(buf, expected);
                var actual = ResidentDialogPayload.STREAM_CODEC.decode(buf);
                assertEquals(expected, actual);
                assertInstanceOf(TranslatableContents.class, actual.message().getContents());
                assertEquals(0, buf.readableBytes());
            } finally { buf.release(); }
        }
        for (var action : ResidentDialogActionPayload.Action.values()) {
            var expected = new ResidentDialogActionPayload(UUID.randomUUID(), 1, action);
            var buf = buffer();
            try { ResidentDialogActionPayload.STREAM_CODEC.encode(buf, expected); assertEquals(expected, ResidentDialogActionPayload.STREAM_CODEC.decode(buf)); }
            finally { buf.release(); }
        }
    }
    @Test void invalidEnumsAndNonpositiveRequestsFailDecode() {
        for (int ordinal : new int[]{-1, 3, Integer.MAX_VALUE}) {
            var buf = buffer();
            try { buf.writeVarInt(ordinal); assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(buf)); }
            finally { buf.release(); }
        }
        for (long request : new long[]{-1, 0, 1}) {
            var buf = buffer();
            try {
                buf.writeUUID(UUID.randomUUID()); buf.writeVarLong(request); buf.writeVarInt(request == 1 ? ResidentDialogActionPayload.Action.values().length : 0);
                assertThrows(RuntimeException.class, () -> ResidentDialogActionPayload.STREAM_CODEC.decode(buf));
            } finally { buf.release(); }
        }
    }
    @Test void negativeCountersAndOversizedListsFailBeforeAllocation() {
        for (int count : new int[]{-1, ResidentDialogPayload.MAX_LINES + 1, Integer.MAX_VALUE}) {
            var buf = buffer();
            try {
                header(buf, 0, 0);
                writeComponent(buf, Component.empty());
                buf.writeUtf("minecraft:overworld"); buf.writeVarInt(count);
                assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(buf));
            } finally { buf.release(); }
        }
        for (long[] counters : new long[][]{{-1, 0}, {0, -1}}) {
            var buf = buffer();
            try { header(buf, counters[0], counters[1]); assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(buf)); }
            finally { buf.release(); }
        }
    }
    private void writeComponent(RegistryFriendlyByteBuf buf, Component value) {
        var nested = buffer();
        try {
            ComponentSerialization.STREAM_CODEC.encode(nested, value);
            buf.writeVarInt(nested.readableBytes()); buf.writeBytes(nested);
        } finally { nested.release(); }
    }
    private void header(RegistryFriendlyByteBuf buf, long revision, long ack) {
        buf.writeVarInt(0); buf.writeUUID(UUID.randomUUID()); buf.writeVarLong(revision); buf.writeVarLong(ack);
    }
    @Test void oversizedTextAndDimensionFailOnWireAndConstruction() {
        var longText = Component.literal("x".repeat(ResidentDialogPayload.MAX_COMPONENT_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> new ResidentDialogPayload(ResidentDialogPayload.Kind.CLOSE, UUID.randomUUID(), 0, 0, null, longText));
        assertThrows(IllegalArgumentException.class, () -> new ResidentDialogPayload.Snapshot(Component.empty(), "x", Collections.nCopies(33, Component.empty()), false, false, false, Component.empty(), Component.empty()));
        var buf = buffer();
        try {
            header(buf, 0, 0); writeComponent(buf, longText);
            assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
        final var dimensionBuf = buffer();
        try {
            header(dimensionBuf, 0, 0); writeComponent(dimensionBuf, Component.empty());
            dimensionBuf.writeUtf("x".repeat(ResidentDialogPayload.MAX_DIMENSION_LENGTH + 1));
            assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(dimensionBuf));
        } finally { dimensionBuf.release(); }
    }
    @Test void componentsAreDetachedAndInvisiblePayloadsAreBounded() {
        var original = Component.literal("Ada");
        var value = new ResidentDialogPayload(ResidentDialogPayload.Kind.CLOSE, UUID.randomUUID(), 0, 0, null, original);
        original.append(" changed");
        assertEquals("Ada", value.message().getString());
        ((net.minecraft.network.chat.MutableComponent) value.message()).append(" changed again");
        assertEquals("Ada", value.message().getString());
        var invisible = Component.empty();
        for (int i = 0; i < 129; i++) invisible.append(Component.empty());
        assertThrows(IllegalArgumentException.class, () -> new ResidentDialogPayload(ResidentDialogPayload.Kind.CLOSE, UUID.randomUUID(), 0, 0, null, invisible));
        var styled = Component.empty().withStyle(style -> style.withInsertion("x".repeat(ResidentDialogPayload.MAX_COMPONENT_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResidentDialogPayload(ResidentDialogPayload.Kind.CLOSE, UUID.randomUUID(), 0, 0, null, styled));
        var frame = buffer();
        try {
            header(frame, 0, 0); frame.writeVarInt(ResidentDialogPayload.MAX_COMPONENT_BYTES + 1);
            assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(frame));
        } finally { frame.release(); }
    }
    @Test void versionedTypesAndStableActionOrdinals() {
        assertEquals("coopcraft:resident_dialog_v2", ResidentDialogPayload.TYPE.id().toString());
        assertEquals("coopcraft:resident_dialog_action_v2", ResidentDialogActionPayload.TYPE.id().toString());
        assertArrayEquals(new ResidentDialogActionPayload.Action[]{
            ResidentDialogActionPayload.Action.REFRESH, ResidentDialogActionPayload.Action.GIFT,
            ResidentDialogActionPayload.Action.INVITE, ResidentDialogActionPayload.Action.CANCEL_INVITE,
            ResidentDialogActionPayload.Action.CLOSE, ResidentDialogActionPayload.Action.HOLD_MEETING
        }, ResidentDialogActionPayload.Action.values());
    }
    @Test void gatheringHintIsDeeplyDetachedIncludingTranslationArguments() {
        // Plain literal translation arguments normalize to Strings in the component codec.
        // Keep a styled, composite argument to exercise the nested Component copy itself.
        var argument = Component.literal("Daily ").withStyle(style -> style.withItalic(true))
            .append(Component.literal("food cap").withStyle(style -> style.withBold(true)));
        var hint = Component.translatable("proposal", argument).append("\n3 residents; 1 social hour per participant");
        var snapshot = new ResidentDialogPayload.Snapshot(Component.empty(), "x", List.of(),
            false, false, false, true, Component.empty(), Component.empty(), hint);
        Component expected = snapshot.gatheringHint();
        argument.append(" changed");
        hint.append(" changed");
        assertEquals(expected, snapshot.gatheringHint());
        var returned = assertInstanceOf(net.minecraft.network.chat.MutableComponent.class, snapshot.gatheringHint());
        var contents = assertInstanceOf(TranslatableContents.class, returned.getContents());
        var returnedArgument = assertInstanceOf(net.minecraft.network.chat.MutableComponent.class, contents.getArgs()[0]);
        returnedArgument.append(" changed");
        returnedArgument.setStyle(returnedArgument.getStyle().withItalic(false));
        var returnedChild = assertInstanceOf(net.minecraft.network.chat.MutableComponent.class, returnedArgument.getSiblings().getFirst());
        returnedChild.append(" changed child");
        returned.append(" changed again");
        assertNotEquals(expected, returned);
        assertEquals(expected, snapshot.gatheringHint());
        var preservedArgument = assertInstanceOf(Component.class,
            ((TranslatableContents) snapshot.gatheringHint().getContents()).getArgs()[0]);
        assertEquals("Daily food cap", preservedArgument.getString());
        assertTrue(preservedArgument.getStyle().isItalic());
        assertTrue(preservedArgument.getSiblings().getFirst().getStyle().isBold());
        assertTrue(snapshot.canHoldMeeting());
    }
    @Test void legacySourceConstructorDisablesMeeting() {
        var snapshot = new ResidentDialogPayload.Snapshot(Component.empty(), "x", List.of(),
            true, true, true, Component.empty(), Component.empty());
        assertFalse(snapshot.canHoldMeeting());
        assertEquals("", snapshot.gatheringHint().getString());
    }
    @Test void meetingHintAndCounterBoundariesRoundTrip() {
        var snapshot = new ResidentDialogPayload.Snapshot(Component.empty(), "x".repeat(ResidentDialogPayload.MAX_DIMENSION_LENGTH),
            Collections.nCopies(ResidentDialogPayload.MAX_LINES, Component.empty()), false, false, false, false,
            Component.empty(), Component.empty(), Component.literal("x".repeat(ResidentDialogPayload.MAX_COMPONENT_LENGTH)));
        var expected = new ResidentDialogPayload(ResidentDialogPayload.Kind.UPDATE, UUID.randomUUID(),
            Long.MAX_VALUE, Long.MAX_VALUE, snapshot, Component.empty());
        var buf = buffer();
        try {
            ResidentDialogPayload.STREAM_CODEC.encode(buf, expected);
            assertEquals(expected, ResidentDialogPayload.STREAM_CODEC.decode(buf));
            assertFalse(expected.snapshot().canHoldMeeting());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
    @Test void oversizedAndInvisibleGatheringHintFailsConstructionAndDecode() {
        var invisible = Component.empty().withStyle(style -> style.withInsertion("x".repeat(ResidentDialogPayload.MAX_COMPONENT_BYTES + 1)));
        for (var hint : List.of(Component.literal("x".repeat(ResidentDialogPayload.MAX_COMPONENT_LENGTH + 1)), invisible)) {
            assertThrows(IllegalArgumentException.class, () -> new ResidentDialogPayload.Snapshot(Component.empty(), "x", List.of(),
                false, false, false, true, Component.empty(), Component.empty(), hint));
        }
        var buf = buffer();
        try {
            header(buf, 0, 0); writeComponent(buf, Component.empty()); buf.writeUtf("x"); buf.writeVarInt(0);
            for (int i = 0; i < 4; i++) buf.writeBoolean(false);
            writeComponent(buf, Component.empty()); writeComponent(buf, Component.empty());
            writeComponent(buf, Component.literal("x".repeat(ResidentDialogPayload.MAX_COMPONENT_LENGTH + 1)));
            writeComponent(buf, Component.empty());
            assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(buf));
        } finally { buf.release(); }
    }
    @Test void invalidActionOrdinalsFailDecode() {
        for (int ordinal : new int[]{-1, ResidentDialogActionPayload.Action.values().length, Integer.MAX_VALUE}) {
            var buf = buffer();
            try {
                buf.writeUUID(UUID.randomUUID()); buf.writeVarLong(1); buf.writeVarInt(ordinal);
                assertThrows(RuntimeException.class, () -> ResidentDialogActionPayload.STREAM_CODEC.decode(buf));
            } finally { buf.release(); }
        }
    }
    @Test void meetingActionContainsOnlyIntentAndEveryTruncatedPrefixFails() {
        var full = buffer();
        try {
            var expected = new ResidentDialogActionPayload(UUID.randomUUID(), Long.MAX_VALUE, ResidentDialogActionPayload.Action.HOLD_MEETING);
            ResidentDialogActionPayload.STREAM_CODEC.encode(full, expected);
            assertEquals(expected.sessionId(), full.readUUID());
            assertEquals(expected.requestId(), full.readVarLong());
            assertEquals(5, full.readVarInt());
            assertFalse(full.isReadable());
            full.readerIndex(0);
            assertEquals(expected, ResidentDialogActionPayload.STREAM_CODEC.decode(full));
            for (int size = 0; size < full.writerIndex(); size++) {
                var prefix = buffer();
                try {
                    prefix.writeBytes(full, 0, size);
                    assertThrows(RuntimeException.class, () -> ResidentDialogActionPayload.STREAM_CODEC.decode(prefix));
                } finally { prefix.release(); }
            }
        } finally { full.release(); }
    }
    @Test void everyTruncatedPrefixFails() {
        var full = buffer();
        try {
            ResidentDialogPayload.STREAM_CODEC.encode(full, new ResidentDialogPayload(ResidentDialogPayload.Kind.OPEN, UUID.randomUUID(), 0, 0, snapshot(), Component.empty()));
            for (int size = 0; size < full.writerIndex(); size++) {
                var prefix = buffer();
                try { prefix.writeBytes(full, 0, size); assertThrows(RuntimeException.class, () -> ResidentDialogPayload.STREAM_CODEC.decode(prefix)); }
                finally { prefix.release(); }
            }
        } finally { full.release(); }
    }
}
