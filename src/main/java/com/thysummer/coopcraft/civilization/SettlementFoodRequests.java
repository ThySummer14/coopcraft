package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Transient, server-thread-only arrival windows. No stock is reserved or consumed here. */
final class SettlementFoodRequests {
	static final long WINDOW_TICKS = 40;
	private final Map<UUID, Batch> batches = new LinkedHashMap<>();

	static final class Ticket {
		private boolean waiting = true;
		private final UUID resident;
		private final String person;
		private final BooleanSupplier present;
		private final Runnable eat;
		private Ticket(UUID resident, String person, BooleanSupplier present, Runnable eat) {
			this.resident = resident;
			this.person = person;
			this.present = present;
			this.eat = eat;
		}
		boolean waiting() { return waiting; }
		void cancel() { waiting = false; }
	}

	private record Batch(long deadline, long gameDay, long coreDay, long modeVersion,
		SettlementSimulationState state, List<Ticket> tickets) { }

	/** Opening a barrel changes only its lid state, not the registered container. */
	static boolean sameStoreState(BlockState expected, BlockState current) {
		return expected.is(Blocks.BARREL)
			? current.is(Blocks.BARREL) && expected.setValue(BarrelBlock.OPEN, false).equals(current.setValue(BarrelBlock.OPEN, false))
			: expected.equals(current);
	}

	static boolean enabled(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement).filter(state -> state.survivalEnabled() && state.normsEnabled())
			.map(state -> state.norms().isNeedFirstEnforced(state.campOwner().id())).orElse(false);
	}

	/** Null means the caller must use its original immediate meal path. Reusing a ticket never revives it. */
	Ticket submit(SettlementSavedData data, Settlement settlement, UUID resident, long gameTick, long gameDay,
		Ticket previous, BooleanSupplier present, Runnable eat) {
		if (previous != null) return previous;
		if (!enabled(data, settlement)) return null;
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String person = state.residentId(resident).orElse(null);
		if (person == null || !member(data, settlement, resident) || !present.getAsBoolean()) {
			Ticket rejected = new Ticket(resident, person, present, eat);
			rejected.cancel();
			return rejected;
		}
		Batch batch = batches.get(settlement.id());
		if (batch != null && !validBatch(data, settlement, batch, gameDay)) {
			cancel(settlement);
			batch = null;
		}
		if (batch == null) {
			batch = new Batch(gameTick + WINDOW_TICKS, gameDay, state.world().day(), state.foodRequestModeVersion(),
				state, new ArrayList<>());
			batches.put(settlement.id(), batch);
		}
		for (Ticket ticket : batch.tickets()) {
			if (ticket.waiting && ticket.resident.equals(resident)) return ticket;
		}
		Ticket ticket = new Ticket(resident, person, present, eat);
		batch.tickets().add(ticket);
		return ticket;
	}

	void cancelResident(UUID resident) {
		for (Batch batch : batches.values()) {
			batch.tickets().stream().filter(ticket -> ticket.resident.equals(resident)).forEach(Ticket::cancel);
		}
	}

	void cancel(Settlement settlement) {
		Batch batch = batches.remove(settlement.id());
		if (batch != null) batch.tickets().forEach(Ticket::cancel);
	}

	void flush(SettlementSavedData data, Settlement settlement, long gameTick, long gameDay) {
		Batch batch = batches.get(settlement.id());
		if (batch == null) return;
		if (!validBatch(data, settlement, batch, gameDay)) {
			cancel(settlement);
			return;
		}
		for (Ticket ticket : batch.tickets()) {
			if (!validTicket(data, settlement, batch, ticket)) ticket.cancel();
		}
		boolean priority = enabled(data, settlement);
		if (priority && gameTick < batch.deadline()) return;
		// Detach before callbacks; neither failures nor reentrant calls can replay this batch.
		batches.remove(settlement.id());
		List<Ticket> ordered = batch.tickets().stream().filter(Ticket::waiting).toList();
		if (priority) {
			Map<String, Ticket> byPerson = new LinkedHashMap<>();
			ordered.forEach(ticket -> byPerson.put(ticket.person, ticket));
			ordered = batch.state().norms().prioritizeFoodRequests(ordered.stream()
				.map(ticket -> batch.state().world().person(ticket.person)).toList()).stream()
				.map(person -> byPerson.get(person.id())).toList();
		}
		try {
			for (Ticket ticket : ordered) {
				boolean valid = validBatch(data, settlement, batch, gameDay) && validTicket(data, settlement, batch, ticket);
				ticket.cancel();
				if (valid) ticket.eat.run();
			}
		} finally {
			batch.tickets().forEach(Ticket::cancel);
		}
	}

	private static boolean validBatch(SettlementSavedData data, Settlement settlement, Batch batch, long gameDay) {
		return data.simulationOf(settlement).orElse(null) == batch.state() && batch.state().survivalEnabled()
			&& batch.state().normsEnabled() && batch.modeVersion() == batch.state().foodRequestModeVersion()
			&& batch.gameDay() == gameDay && batch.coreDay() == batch.state().world().day();
	}

	private static boolean validTicket(SettlementSavedData data, Settlement settlement, Batch batch, Ticket ticket) {
		return ticket.waiting && member(data, settlement, ticket.resident)
			&& batch.state().residentId(ticket.resident).filter(ticket.person::equals).isPresent()
			&& batch.state().world().person(ticket.person).isAlive()
			&& batch.state().world().person(ticket.person).campId().equals(batch.state().campOwner().id())
			&& ticket.present.getAsBoolean();
	}

	private static boolean member(SettlementSavedData data, Settlement settlement, UUID resident) {
		return data.findSettlementForVillager(resident).filter(found -> found.id().equals(settlement.id())).isPresent();
	}
}
