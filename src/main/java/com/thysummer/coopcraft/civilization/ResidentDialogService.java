package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.ArrayList;
import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.net.ResidentDialogActionPayload;
import com.thysummer.coopcraft.net.ResidentDialogPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Server-authoritative resident conversation, independent of vanilla trading and container inventories. */
public final class ResidentDialogService {
	private static final ResidentDialogSession.Registry<Binding> SESSIONS = new ResidentDialogSession.Registry<>();
	private static boolean registered;
	private ResidentDialogService() {}

	private static final class Binding {
		final ServerPlayer player;
		final ServerLevel level;
		final Villager villager;
		final SettlementSavedData data;
		final Settlement settlement;
		final Object member;
		ResidentDialogPayload lastPayload;
		SettlementMeetings.Preview observedMeeting;
		List<Component> lastMeetingReceipt = List.of();
		Binding(ServerPlayer player, Villager villager, SettlementSavedData data, Settlement settlement, ServerLevel level) {
			this.player = player; this.villager = villager; this.data = data; this.settlement = settlement; this.level = level;
			this.member = data.memberIdentity(villager.getUUID());
		}
	}

	/** False means protocol unavailable only; callers retain chat description, never vanilla trade. */
	public static boolean open(ServerPlayer player, Villager villager, SettlementSavedData data, Settlement settlement) {
		if (!ServerPlayNetworking.canSend(player, ResidentDialogPayload.TYPE)) return false;
		if (!(villager.level() instanceof ServerLevel level)) return rejectOpen(player);
		Binding binding = new Binding(player, villager, data, settlement, level);
		try {
			if (!valid(binding, false)) return rejectOpen(player);
			var previous = SESSIONS.get(player.getUUID());
			if (previous != null) close(previous, message("replaced"), true);
			// Vanilla owns cursor/crafting-item restitution. Never assign containerMenu or touch its slots here.
			if (player.containerMenu != player.inventoryMenu) player.closeContainer();
			if (!valid(binding, true)) return rejectOpen(player);
			// A callback while closing the real menu may already have opened a newer conversation.
			if (SESSIONS.get(player.getUUID()) != null) return true;
			var session = new ResidentDialogSession<>(binding, level.getGameTime());
			if (!SESSIONS.add(player.getUUID(), session)) return rejectOpen(player);
			try { publish(session, ResidentDialogPayload.Kind.OPEN, Component.empty()); }
			catch (RuntimeException exception) { fail(session, exception); }
		} catch (RuntimeException exception) {
			CoopCraft.LOGGER.warn("Unable to open resident conversation", exception);
			return rejectOpen(player);
		}
		return true;
	}

	public static void handle(ServerPlayer player, ResidentDialogActionPayload payload) {
		var session = SESSIONS.get(player.getUUID());
		if (session == null || session.binding.player != player || !session.matchesToken(payload.sessionId())) return;
		if (payload.action() == ResidentDialogActionPayload.Action.CLOSE) {
			close(session, Component.empty(), true);
			return;
		}
		try {
			Binding b = session.binding;
			if (!session.alive(b.level.getGameTime()) || !valid(b, true)) {
				close(session, message("unavailable"), true);
				return;
			}
			var request = session.claim(payload.requestId(), b.level.getGameTime());
			if (request == ResidentDialogSession.Request.DUPLICATE) {
				// Replay the exact published view/ack. Do not replace the cancel ticket with a newer ticket.
				if (b.lastPayload != null) ServerPlayNetworking.send(player, b.lastPayload);
				return;
			}
			if (request != ResidentDialogSession.Request.NEXT) {
				close(session, message("sequence"), true);
				return;
			}
			// Validate the read-only view/codec before any effect, without changing the last shown cancel ticket.
			prepare(session);
			Component result = switch (payload.action()) {
				case REFRESH -> Component.empty();
				case GIFT -> {
					var gift = VillagerInteraction.offerFood(player, b.villager, b.settlement, b.data);
					yield gift.accepted() && gift.moved() == 1 ? message("gift_accepted")
						: Component.translatable("coopcraft.interaction.refused", safeName(b.villager),
							Component.translatable("coopcraft.interaction.reason." + gift.reason()));
				}
				case INVITE, CANCEL_INVITE -> {
					String outcome = VillagerWorkGoal.requestMeetingInvitation(player, b.villager, b.data, b.settlement,
						payload.action() == ResidentDialogActionPayload.Action.CANCEL_INVITE, session.observedInvitation());
					yield Component.translatable("coopcraft.interaction.meeting." + outcome, safeName(b.villager));
				}
				case HOLD_MEETING -> {
					// Use the review actually sent to this player, not the fresh preflight above.
					var meeting = SettlementMeetings.holdReviewed(b.level, b.data, b.settlement, player, b.observedMeeting);
					if (meeting.held()) b.lastMeetingReceipt = ResidentMeetingView.receipt(meeting);
					yield ResidentMeetingView.status(meeting);
				}
				case CLOSE -> throw new IllegalStateException("Close was already handled");
			};
			// Services may invoke callbacks. Never resurrect or publish over a replacement session.
			if (SESSIONS.get(player.getUUID()) != session) return;
			if (!valid(b, true)) { close(session, message("unavailable"), true); return; }
			session.nextRevision();
			publish(session, ResidentDialogPayload.Kind.UPDATE, result);
		} catch (RuntimeException exception) { fail(session, exception); }
	}

	public static void registerLifecycle() {
		if (registered) return;
		registered = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (var session : SESSIONS.all()) {
				if (session.binding.level.getServer() != server) continue;
				try {
					if (!session.alive(session.binding.level.getGameTime()) || !valid(session.binding, true)) {
						close(session, message("unavailable"), true);
					}
				} catch (RuntimeException exception) { fail(session, exception); }
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var session = SESSIONS.get(handler.player.getUUID());
			if (session != null && session.binding.player == handler.player) close(session, Component.empty(), false);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			for (var session : SESSIONS.all()) {
				if (session.binding.villager == entity || session.binding.player == entity) close(session, message("unavailable"), true);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (var session : SESSIONS.all()) {
				if (session.binding.level.getServer() == server) close(session, Component.empty(), false);
			}
		});
	}

	/** Shared production validation seam, independent of membership/trade routing. */
	record Presence(boolean samePlayer, boolean playerAlive, boolean notSpectator, boolean sameLevel,
		boolean villagerAlive, boolean sameVillager, boolean loaded, boolean ticking,
		boolean visible, boolean awake, boolean notTrading, boolean noOtherMenu, double distanceSquared) {
		Presence withVisibility(boolean visible) {
			return new Presence(samePlayer, playerAlive, notSpectator, sameLevel, villagerAlive, sameVillager, loaded, ticking,
				visible, awake, notTrading, noOtherMenu, distanceSquared);
		}
		boolean eligible(boolean requireNoMenu) {
			return samePlayer && playerAlive && notSpectator && sameLevel && villagerAlive && sameVillager
				&& loaded && ticking && visible && awake && notTrading && (!requireNoMenu || noOtherMenu)
				&& Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 16;
		}
	}

	static boolean matchesClaim(SettlementMembershipClaims.Resolution resolution, ResourceKey<Level> dimension,
		SettlementSavedData data, Settlement settlement, Object member) {
		return member != null && resolution.uniqueClaim().filter(claim -> claim.dimension().equals(dimension)
			&& claim.data() == data && claim.settlement() == settlement && claim.member() == member).isPresent();
	}

	private static boolean valid(Binding b, boolean requireNoMenu) {
		ServerPlayer player = b.player;
		Villager villager = b.villager;
		// Every argument here is a non-loading check. Visibility is evaluated separately only after admission.
		Presence scene = new Presence(b.level.getServer().getPlayerList().getPlayer(player.getUUID()) == player,
			player.isAlive() && !player.isRemoved(), !player.isSpectator(), player.level() == b.level && villager.level() == b.level,
			villager.isAlive() && !villager.isRemoved(), b.level.getEntity(villager.getUUID()) == villager,
			b.level.isLoaded(villager.blockPosition()), b.level.isPositionEntityTicking(villager.blockPosition()),
			true, !villager.isSleeping(), !villager.isTrading(), player.containerMenu == player.inventoryMenu, player.distanceToSqr(villager));
		if (!scene.eligible(requireNoMenu)) return false;
		// A diagonal ray can cross a third chunk even when both endpoint chunks are loaded.
		// Check the small enclosing X/Z chunk rectangle using isLoaded only, never getChunk.
		int minX = Math.min(player.blockPosition().getX(), villager.blockPosition().getX()) >> 4;
		int maxX = Math.max(player.blockPosition().getX(), villager.blockPosition().getX()) >> 4;
		int minZ = Math.min(player.blockPosition().getZ(), villager.blockPosition().getZ()) >> 4;
		int maxZ = Math.max(player.blockPosition().getZ(), villager.blockPosition().getZ()) >> 4;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!b.level.isLoaded(new BlockPos(x << 4, player.blockPosition().getY(), z << 4))) return false;
			}
		}
		return scene.withVisibility(player.hasLineOfSight(villager)).eligible(requireNoMenu)
			&& SettlementSavedData.get(b.level) == b.data
			&& matchesClaim(SettlementMembershipClaims.resolve(b.level, villager.getUUID()), b.level.dimension(), b.data, b.settlement, b.member);
	}

	private record Prepared(ResidentDialogPayload.Snapshot snapshot, Object ticket, SettlementMeetings.Preview meeting) {}

	/** No ticket observation, packets, resource changes or invitation lifetime changes. */
	private static Prepared prepare(ResidentDialogSession<Binding> session) {
		Binding b = session.binding;
		Object ticket = VillagerWorkGoal.invitationIdentity(b.villager);
		boolean owned = ticket != null && VillagerWorkGoal.invitationOwnedBy(b.villager, b.player.getUUID());
		String gate = SettlementMeetingInvitations.gate(b.data, b.settlement,
			b.villager.getUUID(), b.level.getDayTime() / Level.TICKS_PER_DAY);
		boolean canInvite = ticket == null && gate.equals("ready");
		boolean canGift = giftAvailable(b.player.getMainHandItem(), SettlementSimulationService.isSurvivalEnabled(b.data, b.settlement));
		Component meetingHint = ticket != null
			? (owned ? message("own_invitation") : Component.translatable("coopcraft.interaction.meeting.already_invited", safeName(b.villager)))
			: canInvite ? message("meeting_hint") : Component.translatable("coopcraft.interaction.meeting." + gate, safeName(b.villager));
		var meeting = SettlementMeetings.preview(b.level, b.data, b.settlement, b.player);
		var meetingView = ResidentMeetingView.present(meeting.result());
		boolean canHold = meetingView.canHold();
		List<Component> details = new ArrayList<>(b.lastMeetingReceipt);
		details.addAll(VillagerInteraction.inspectLines(b.player, b.villager, b.settlement, b.data));
		details.addAll(ResidentMeetingView.rations(b.data, b.settlement, b.villager.getUUID(), safeName(b.villager),
			b.level.getDayTime() / Level.TICKS_PER_DAY));
		List<Component> lines = details.stream()
			.limit(ResidentDialogPayload.MAX_LINES).map(ResidentDialogService::bounded).toList();
		var snapshot = new ResidentDialogPayload.Snapshot(safeName(b.villager),
			b.level.dimension().location().toString(), lines, canGift, canInvite, owned, canHold,
			message(canGift ? "gift_hint" : "gift_unavailable"), bounded(meetingHint),
			meetingView.hint());
		return new Prepared(snapshot, ticket, canHold ? meeting : null);
	}

	static boolean giftAvailable(ItemStack currentHand, boolean survival) {
		return currentHand.getComponentsPatch().isEmpty() && VillagerInteraction.isSupportedFood(currentHand, survival);
	}

	private static void publish(ResidentDialogSession<Binding> session, ResidentDialogPayload.Kind kind, Component result) {
		Binding b = session.binding;
		Prepared prepared = prepare(session);
		var payload = new ResidentDialogPayload(kind, session.token, session.revision(), session.acknowledgedRequest(), prepared.snapshot(), bounded(result));
		session.observeInvitation(prepared.ticket());
		b.observedMeeting = prepared.meeting();
		b.lastPayload = payload;
		ServerPlayNetworking.send(b.player, payload);
	}

	private static Component safeName(Villager villager) {
		return bounded(Component.literal(VillagerInteraction.nameOf(villager).getString()));
	}

	private static Component bounded(Component component) {
		String text = component.getString();
		if (text.length() <= ResidentDialogPayload.MAX_COMPONENT_LENGTH) return component;
		int end = ResidentDialogPayload.MAX_COMPONENT_LENGTH - 1;
		if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
		return Component.literal(text.substring(0, end) + "…");
	}

	private static boolean rejectOpen(ServerPlayer player) {
		player.sendSystemMessage(message("unavailable"));
		return true;
	}
	private static Component message(String suffix) { return Component.translatable("coopcraft.resident_dialog." + suffix); }
	private static void close(ResidentDialogSession<Binding> session, Component reason, boolean notify) {
		Binding b = session.binding;
		if (!SESSIONS.remove(b.player.getUUID(), session)) return;
		b.lastPayload = null;
		b.observedMeeting = null;
		b.lastMeetingReceipt = List.of();
		if (session.revision() < Long.MAX_VALUE) session.nextRevision();
		if (!notify || b.level.getServer().getPlayerList().getPlayer(b.player.getUUID()) != b.player) return;
		try {
			if (ServerPlayNetworking.canSend(b.player, ResidentDialogPayload.TYPE)) {
				ServerPlayNetworking.send(b.player, new ResidentDialogPayload(ResidentDialogPayload.Kind.CLOSE, session.token,
					session.revision(), session.acknowledgedRequest(), null, bounded(reason)));
			}
		} catch (RuntimeException exception) { CoopCraft.LOGGER.debug("Resident conversation already disconnected", exception); }
	}
	private static void fail(ResidentDialogSession<Binding> session, RuntimeException exception) {
		CoopCraft.LOGGER.warn("Closing failed resident conversation", exception);
		close(session, message("unavailable"), true);
	}
}
