package com.thysummer.coopcraft.client;

import java.util.UUID;
import com.thysummer.coopcraft.net.ResidentDialogActionPayload;
import com.thysummer.coopcraft.net.ResidentDialogClientState;
import com.thysummer.coopcraft.net.ResidentDialogPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class ResidentDialogScreens {
    private static final ResidentDialogClientState STATE = new ResidentDialogClientState();
    private ResidentDialogScreens() {}
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ResidentDialogPayload.TYPE, (packet, context) -> {
            var receivedLevel = context.client().level;
            context.client().execute(() -> {
                if (context.client().level == receivedLevel) receive(context.client(), packet);
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(ResidentDialogScreens::validate);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            STATE.terminate(STATE.sessionId());
            if (client.screen instanceof ResidentDialogScreen) client.setScreen(null);
        });
    }
    private static UUID screenId(Minecraft client) {
        return client.screen instanceof ResidentDialogScreen screen ? screen.sessionId() : null;
    }
    private static String dimension(Minecraft client) {
        return client.level == null ? null : client.level.dimension().location().toString();
    }
    private static boolean alive(Minecraft client) {
        return client.player != null && client.player.isAlive() && client.level != null;
    }
    private static void validate(Minecraft client) {
        UUID previous = STATE.sessionId();
        send(STATE.synchronize(client.level, dimension(client), alive(client), screenId(client)));
        if (previous != null && !STATE.isActive(previous) && previous.equals(screenId(client))) client.setScreen(null);
    }
    private static void receive(Minecraft client, ResidentDialogPayload packet) {
        validate(client);
        var decision = STATE.accept(packet, client.level, dimension(client), alive(client), screenId(client), client.screen != null);
        switch (decision) {
            case OPEN -> client.setScreen(new ResidentDialogScreen(packet));
            case UPDATE -> {
                if (client.screen instanceof ResidentDialogScreen screen) screen.update(packet);
            }
            case CLOSE -> {
                if (!packet.message().getString().isEmpty() && client.player != null) client.player.displayClientMessage(packet.message(), false);
                client.setScreen(null);
            }
            case REJECT_OPEN -> send(new ResidentDialogActionPayload(packet.sessionId(), 1, ResidentDialogActionPayload.Action.CLOSE));
            case IGNORE -> { }
        }
    }
    static boolean pending(UUID id) { return !STATE.isActive(id) || STATE.pending(); }
    static void action(UUID id, ResidentDialogActionPayload.Action action) {
        validate(Minecraft.getInstance());
        if (ClientPlayNetworking.canSend(ResidentDialogActionPayload.TYPE)) send(STATE.request(id, action));
    }
    static void close(UUID id) { send(STATE.terminate(id)); }
    private static void send(ResidentDialogActionPayload packet) {
        if (packet != null && ClientPlayNetworking.canSend(ResidentDialogActionPayload.TYPE)) ClientPlayNetworking.send(packet);
    }
}
