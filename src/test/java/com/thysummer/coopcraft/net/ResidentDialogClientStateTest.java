package com.thysummer.coopcraft.net;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.net.ResidentDialogClientState.Decision.*;

import static com.thysummer.coopcraft.net.ResidentDialogActionPayload.Action.*;

class ResidentDialogClientStateTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private final ResidentDialogClientState state = new ResidentDialogClientState();
    private final Object world = new Object();
    private final UUID id = UUID.randomUUID();
    private static final String DIM = "minecraft:overworld";
    private ResidentDialogPayload packet(ResidentDialogPayload.Kind kind, UUID token, long revision, long ack) {
        return new ResidentDialogPayload(kind, token, revision, ack, kind == ResidentDialogPayload.Kind.CLOSE ? null
            : new ResidentDialogPayload.Snapshot(Component.literal("Ada"), DIM, List.of(), true, true, false,
                Component.empty(), Component.empty()), Component.empty());
    }
    private void open() { assertEquals(OPEN, state.accept(packet(ResidentDialogPayload.Kind.OPEN, id, 0, 0), world, DIM, true, null, false)); }
    @Test void closedUpdateAndDuplicateOpenNeverReopen() {
        open();
        assertNotNull(state.terminate(id));
        assertNull(state.terminate(id));
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 5, 0), world, DIM, true, null, false));
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.OPEN, id, 6, 0), world, DIM, true, null, false));
    }
    @Test void unrelatedScreenRejectsOpenAndUpdateDoesNotReplaceIt() {
        assertEquals(REJECT_OPEN, state.accept(packet(ResidentDialogPayload.Kind.OPEN, id, 0, 0), world, DIM, true, null, true));
        UUID other = UUID.randomUUID();
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, other, 1, 0), world, DIM, true, null, true));
        assertNull(state.sessionId());
    }
    @Test void oldCloseAndDuplicateOpenCannotAffectNewResidentScreen() {
        open(); UUID next = UUID.randomUUID();
        assertEquals(OPEN, state.accept(packet(ResidentDialogPayload.Kind.OPEN, next, 0, 0), world, DIM, true, id, true));
        assertNull(state.terminate(id)); // removed() from replaced screen
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.CLOSE, id, 2, 0), world, DIM, true, next, true));
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.OPEN, id, 2, 0), world, DIM, true, next, true));
        assertTrue(state.isActive(next));
    }
    @Test void revisionAndPendingAckAreIndependentAndResizePreservesSequence() {
        open();
        assertEquals(1, state.request(id, GIFT).requestId());
        assertNull(state.request(id, GIFT));
        assertNull(state.synchronize(world, DIM, true, id)); // resize retains screen/session/world identity
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 0, 1), world, DIM, true, id, true));
        assertTrue(state.pending());
        assertEquals(ResidentDialogClientState.Decision.UPDATE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 1, 0), world, DIM, true, id, true));
        assertTrue(state.pending());
        assertEquals(ResidentDialogClientState.Decision.UPDATE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 2, 1), world, DIM, true, id, true));
        assertFalse(state.pending());
        assertEquals(2, state.request(id, REFRESH).requestId());
        assertEquals(3, state.terminate(id).requestId()); // close can terminate a pending action
        assertNull(state.terminate(id));
    }
    @Test void changedWorldDeathDisconnectAndNewerUiInvalidate() {
        open();
        assertNotNull(state.synchronize(new Object(), DIM, true, id));
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 1, 0), world, DIM, true, id, true));
        for (int mode = 0; mode < 3; mode++) {
            UUID next = UUID.randomUUID();
            assertEquals(OPEN, state.accept(packet(ResidentDialogPayload.Kind.OPEN, next, 0, 0), world, DIM, true, null, false));
            assertNotNull(state.synchronize(mode == 0 ? null : world, DIM, mode != 1, mode == 2 ? null : next));
        }
    }
    @Test void mismatchedDimensionCannotOpenOrUpdate() {
        assertEquals(REJECT_OPEN, state.accept(packet(ResidentDialogPayload.Kind.OPEN, id, 0, 0), world, "minecraft:the_nether", true, null, false));
        UUID next = UUID.randomUUID();
        assertEquals(OPEN, state.accept(packet(ResidentDialogPayload.Kind.OPEN, next, 0, 0), world, DIM, true, null, false));
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, next, 1, 0), world, "minecraft:the_nether", true, next, true));
    }
    @Test void futureAndRegressingAcknowledgementsNeverEnableActions() {
        open(); state.request(id, GIFT);
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 1, 2), world, DIM, true, id, true));
        assertTrue(state.pending());
        assertEquals(UPDATE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 1, 1), world, DIM, true, id, true));
        state.request(id, REFRESH);
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 2, 0), world, DIM, true, id, true));
        assertTrue(state.pending());
    }
    @Test void holdMeetingUsesSinglePendingRequestAndClosedUpdateCannotReopen() {
        open();
        var request = state.request(id, HOLD_MEETING);
        assertEquals(HOLD_MEETING, request.action());
        assertEquals(1, request.requestId());
        assertNull(state.request(id, HOLD_MEETING));
        assertNull(state.request(id, REFRESH));
        assertNull(state.synchronize(world, DIM, true, id)); // resizing keeps the same pending action
        assertTrue(state.pending());
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 0, 1), world, DIM, true, id, true));
        assertTrue(state.pending());
        assertEquals(UPDATE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 1, 1), world, DIM, true, id, true));
        assertFalse(state.pending());
        assertEquals(2, state.request(id, HOLD_MEETING).requestId());
        assertEquals(3, state.terminate(id).requestId());
        assertEquals(IGNORE, state.accept(packet(ResidentDialogPayload.Kind.UPDATE, id, 2, 2), world, DIM, true, null, false));
        assertNull(state.sessionId());
    }
    @Test void serverCloseTerminatesWithoutEcho() {
        open();
        assertEquals(ResidentDialogClientState.Decision.CLOSE, state.accept(packet(ResidentDialogPayload.Kind.CLOSE, id, 1, 0), world, DIM, true, id, true));
        assertNull(state.terminate(id));
    }
}
