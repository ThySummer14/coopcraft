package com.thysummer.coopcraft.net;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The production acceptance/sequence state machine, independent of client-only Minecraft classes. */
public final class ResidentDialogClientState {
    public static final int MAX_RETIRED_SESSIONS = 128;
    public enum Decision { OPEN, UPDATE, CLOSE, IGNORE, REJECT_OPEN }
    private final Set<UUID> retired = new LinkedHashSet<>();
    private UUID session;
    private Object world;
    private String dimension;
    private long revision = -1;
    private long lastRequest;
    private long pending;
    private long acknowledged;

    public UUID sessionId() { return session; }
    public long revision() { return revision; }
    public long pendingRequest() { return pending; }
    public boolean pending() { return pending != 0; }
    public boolean isActive(UUID id) { return id != null && id.equals(session); }

    /** Called before packets and each tick; world identity matters even in the same dimension. */
    public ResidentDialogActionPayload synchronize(Object level, String currentDimension, boolean alive, UUID actualScreen) {
        if (session != null && (!alive || level != world || !Objects.equals(dimension, currentDimension)
                || !session.equals(actualScreen))) return terminate(session);
        return null;
    }

    public Decision accept(ResidentDialogPayload packet, Object level, String currentDimension,
                           boolean alive, UUID actualScreen, boolean occupied) {
        UUID id = packet.sessionId();
        if (packet.kind() == ResidentDialogPayload.Kind.OPEN) {
            if (retired.contains(id) || isActive(id)) return Decision.IGNORE;
            if (packet.acknowledgedRequest() != 0 || !alive || level == null || !packet.snapshot().dimension().equals(currentDimension)
                    || (occupied && !isActive(actualScreen))) {
                retire(id);
                return Decision.REJECT_OPEN;
            }
            if (session != null) retire(session);
            session = id; world = level; dimension = currentDimension;
            revision = packet.revision(); lastRequest = 0; pending = 0; acknowledged = 0;
            return Decision.OPEN;
        }
        if (!isActive(id) || !id.equals(actualScreen) || level != world || !alive
                || !Objects.equals(dimension, currentDimension)) return Decision.IGNORE;
        if (packet.revision() <= revision) return Decision.IGNORE;
        if (packet.kind() == ResidentDialogPayload.Kind.CLOSE) {
            retire(session); clear();
            return Decision.CLOSE;
        }
        if (!packet.snapshot().dimension().equals(dimension)) return Decision.IGNORE;
        if (packet.acknowledgedRequest() < acknowledged || packet.acknowledgedRequest() > lastRequest) return Decision.IGNORE;
        revision = packet.revision();
        acknowledged = packet.acknowledgedRequest();
        if (pending != 0 && packet.acknowledgedRequest() >= pending) pending = 0;
        return Decision.UPDATE;
    }

    public ResidentDialogActionPayload request(UUID id, ResidentDialogActionPayload.Action action) {
        if (action == ResidentDialogActionPayload.Action.CLOSE) return terminate(id);
        if (!isActive(id) || pending() || lastRequest == Long.MAX_VALUE) return null;
        pending = ++lastRequest;
        return new ResidentDialogActionPayload(session, pending, action);
    }

    /** Closing is allowed while another action is pending, and happens at most once. */
    public ResidentDialogActionPayload terminate(UUID id) {
        if (!isActive(id)) return null;
        ResidentDialogActionPayload close = lastRequest == Long.MAX_VALUE ? null
            : new ResidentDialogActionPayload(session, ++lastRequest, ResidentDialogActionPayload.Action.CLOSE);
        retire(session); clear();
        return close;
    }
    private void clear() { session = null; world = null; dimension = null; revision = -1; pending = 0; lastRequest = 0; acknowledged = 0; }
    private void retire(UUID id) {
        retired.add(id);
        while (retired.size() > MAX_RETIRED_SESSIONS) retired.remove(retired.iterator().next());
    }
}
