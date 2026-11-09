package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;

/** Ephemeral consent to travel, never a discussion, reservation or vote. */
final class SettlementMeetingInvitations {
	static final long LIFETIME_TICKS = 1200;
	static final long WAIT_TICKS = 600;
	static final int MAX_TRAVEL_TICKS = 400;
	private SettlementMeetingInvitations() {}

	record Presence(boolean alive, boolean loaded, boolean entityTicking, boolean sameLevel,
		boolean adult, boolean ai, boolean awake, boolean notTrading, boolean notRiding,
		boolean healthy, boolean notEating, double settlementDistanceSquared) {
		boolean eligible() {
			return alive && loaded && entityTicking && sameLevel && adult && ai && awake && notTrading
				&& notRiding && healthy && notEating && withinSettlement(settlementDistanceSquared);
		}
	}
	static boolean withinSettlement(double distanceSquared) {
		return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 64 * 64;
	}
	static String existing(Ticket ticket, UUID inviter) {
		return ticket.inviter().equals(inviter) ? "cancelled" : "already_invited";
	}
	static boolean cancelOnStop(boolean attending) { return attending; }

	static boolean waitingPositionValid(boolean waiting, double distanceSquared) {
		return !waiting || arrived(distanceSquared);
	}

	static boolean arrived(double distanceSquared) {
		return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 9;
	}

	static String gate(SettlementSavedData data, Settlement settlement, UUID resident, long gameDay) {
		var state = data.simulationOf(settlement).orElse(null);
		if (!data.all().contains(settlement) || state == null || !state.survivalEnabled() || !state.normsEnabled()) return "disabled";
		if (!SettlementMeetings.dayReady(gameDay, state.lastSurvivalDay())) return "day_pending";
		if (state.norms().lastMeetingDay(state.campOwner().id()) == state.world().day()) return "already_held";
		if (state.world().proposalsForCamp(state.campOwner().id()).stream().noneMatch(p -> p.status() == NormProposal.Status.OPEN)) return "no_proposal";
		String id = state.residentId(resident).orElse(null);
		if (!data.isMember(settlement, resident) || id == null) return "not_member";
		var person = state.world().person(id);
		if (!person.isAlive() || !person.canMakeDecisions() || !person.campId().equals(state.campOwner().id())) return "unavailable";
		if (100 - person.hunger() <= SettlementNeeds.HUNGRY_AT) return "hungry";
		if (person.socialHours() >= 6) return "exhausted";
		return "ready";
	}

	static Ticket create(SettlementSavedData data, Settlement settlement, UUID resident, UUID inviter,
		long tick, long gameDay, BooleanSupplier sceneValid) {
		if (tick < 0 || tick > Long.MAX_VALUE - LIFETIME_TICKS || !gate(data, settlement, resident, gameDay).equals("ready") || !sceneValid.getAsBoolean()) return null;
		return new Ticket(data, settlement, resident, inviter, tick, gameDay, sceneValid);
	}

	static final class Ticket {
		private final SettlementSavedData data;
		private final Settlement settlement;
		private final SettlementSimulationState state;
		private final Object member, stockpileBinding;
		private final UUID resident, inviter;
		private final String person;
		private final long created, deadline, gameDay, coreDay, modeVersion;
		private final BooleanSupplier sceneValid;
		private boolean active = true, waiting;
		private long waitingDeadline = Long.MAX_VALUE;

		private Ticket(SettlementSavedData data, Settlement settlement, UUID resident, UUID inviter,
			long tick, long gameDay, BooleanSupplier sceneValid) {
			this.data = data; this.settlement = settlement; this.resident = resident; this.inviter = inviter;
			this.state = data.simulationOf(settlement).orElseThrow();
			this.member = data.memberIdentity(resident); this.stockpileBinding = data.stockpileBindingToken(settlement);
			this.person = state.residentId(resident).orElseThrow();
			this.created = tick; this.deadline = tick + LIFETIME_TICKS; this.gameDay = gameDay;
			this.coreDay = state.world().day(); this.modeVersion = state.foodRequestModeVersion(); this.sceneValid = sceneValid;
		}
		boolean valid(SettlementSavedData current, long tick, long day) {
			if (!matches(current, tick, day)) cancel();
			return active;
		}
		boolean matches(SettlementSavedData current, long tick, long day) {
			if (!active) return false;
			return !(current != data || tick < created || tick >= deadline || tick >= waitingDeadline || day != gameDay
				|| current.simulationOf(settlement).orElse(null) != state || state.world().day() != coreDay
				|| state.foodRequestModeVersion() != modeVersion || current.memberIdentity(resident) != member
				|| current.stockpileBindingToken(settlement) != stockpileBinding
				|| !state.residentId(resident).filter(person::equals).isPresent()
				|| !gate(current, settlement, resident, day).equals("ready") || !sceneValid.getAsBoolean());
		}
		void arrive(long tick) { if (active && !waiting && tick >= created && tick < deadline) { waiting = true; waitingDeadline = tick > Long.MAX_VALUE - WAIT_TICKS ? deadline : Math.min(deadline, tick + WAIT_TICKS); } }
		boolean waiting() { return active && waiting; }
		void cancel() { active = false; }
		UUID inviter() { return inviter; }
		long deadline() { return deadline; }
	}
}
