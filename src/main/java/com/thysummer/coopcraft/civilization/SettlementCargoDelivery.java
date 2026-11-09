package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;

/**
 * Transient server-thread delivery authorization. No item snapshots or abstract inventory are retained.
 * Physical transfers require distinct, non-overlapping standard containers with synchronous,
 * non-reentrant mutation semantics. World/store entity identity is checked by the caller's closure.
 */
public final class SettlementCargoDelivery {
	private SettlementCargoDelivery() { }

	public enum Status { INVALID, NOT_AT_STORE, NO_CAPACITY, FOOD_UNAVAILABLE, DELIVERED }
	public record Result(Status status, int moved) { }

	/** A real arrival may only feed the simulation for a currently living, same-camp resident. */
	public static boolean hasDeliverableFood(SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		SettlementSimulationState state = foodState(data, camp, actor);
		return state != null && SettlementAgedFoodCargo.hasDeliverable(state, inventory);
	}

	public static boolean hasMarkedBerries(Container inventory) {
		if (inventory == null) return false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			var stack = inventory.getItem(slot);
			if (stack.is(net.minecraft.world.item.Items.SWEET_BERRIES) && SettlementFoodAge.isMarked(stack)) return true;
		}
		return false;
	}

	private static SettlementSimulationState foodState(SettlementSavedData data, Settlement camp, UUID actor) {
		if (data == null || camp == null || actor == null || !data.isMember(camp, actor)) return null;
		SettlementSimulationState state = data.simulationOf(camp).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.productionEnabled()) return null;
		return state.residentId(actor)
			.flatMap(id -> state.world().allPeople().stream().filter(person -> person.id().equals(id)).findFirst())
			.filter(person -> person.isAlive() && person.campId().equals(state.campOwner().id()))
			.map(person -> state).orElse(null);
	}

	public static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor,
		Container inventory, BooleanSupplier identityAndPresence) {
		if (data == null || camp == null || actor == null || inventory == null || identityAndPresence == null
			|| !identityAndPresence.getAsBoolean()
			|| !data.isMember(camp, actor) || data.getStockpile(camp).isEmpty()) return null;
		return new Ticket(data, camp, actor, inventory, identityAndPresence);
	}

	/** Lightweight per-tick identity check; deliberately does not inspect either inventory. */
	public static boolean valid(Ticket ticket, SettlementSavedData data, Settlement camp, UUID actor,
		Container inventory) {
		return ticket != null && !ticket.used && matches(ticket, data, camp, actor, inventory);
	}

	private static boolean matches(Ticket ticket, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		return data == ticket.data && camp != null
			&& ticket.identityAndPresence.getAsBoolean()
			&& ticket.survival == SettlementSimulationService.isSurvivalEnabled(data, camp)
			&& ticket.simulation == data.simulationOf(camp).orElse(null)
			&& (ticket.simulation == null || ticket.modeVersion == ticket.simulation.foodRequestModeVersion())
			&& ticket.camp.equals(camp.id()) && ticket.actor.equals(actor) && inventory == ticket.inventory
			&& data.isMember(camp, actor) && ticket.member == data.memberIdentity(actor)
			&& ticket.binding == data.stockpileBindingToken(camp)
			&& data.getStockpile(camp).filter(ticket.store::equals).isPresent();
	}

	public static Result finish(Ticket ticket, SettlementSavedData data, Settlement camp, UUID actor,
		Container inventory, Container currentStore, BooleanSupplier atStore) {
		if (ticket == null || ticket.used) return new Result(Status.INVALID, 0);
		// Every final attempt is consumed, including failed identity or arrival checks.
		ticket.used = true;
		if (!matches(ticket, data, camp, actor, inventory) || currentStore == null || currentStore == inventory || atStore == null) return new Result(Status.INVALID, 0);
		if (!atStore.getAsBoolean()) return new Result(Status.NOT_AT_STORE, 0);
		if (!matches(ticket, data, camp, actor, inventory)) return new Result(Status.INVALID, 0);
		// Food gets one full arrival-capacity snapshot. Its accepted stock exists only in the ledger.
		SettlementSimulationState foodState = foodState(data, camp, actor);
		int moved;
		try {
			moved = foodState == null ? 0 : SettlementAgedFoodCargo.deliver(foodState, inventory, currentStore,
				() -> matches(ticket, data, camp, actor, inventory) && atStore.getAsBoolean()
					&& matches(ticket, data, camp, actor, inventory) && foodState(data, camp, actor) == foodState);
		} catch (IllegalArgumentException invalidSimulation) {
			// Expected core preflight failures (for example exhausted IDs) occur before any cargo write.
			return new Result(Status.INVALID, 0);
		}
		if (moved > 0) data.setDirty();
		if (moved == 0 && (!matches(ticket, data, camp, actor, inventory) || !atStore.getAsBoolean()
			|| !matches(ticket, data, camp, actor, inventory))) return new Result(Status.INVALID, 0);
		boolean materialCargo = SettlementCargo.hasCargo(inventory);
		moved += SettlementStockpile.depositGatheredResources(currentStore, inventory);
		if (!ticket.survival) moved += SettlementFoodCargo.depositSurplus(data, camp, actor, inventory, currentStore);
		if (moved == 0 && !materialCargo && hasMarkedBerries(inventory)
			&& !hasDeliverableFood(data, camp, actor, inventory)) return new Result(Status.FOOD_UNAVAILABLE, 0);
		return new Result(moved > 0 ? Status.DELIVERED : Status.NO_CAPACITY, moved);
	}

	public static final class Ticket {
		private final SettlementSavedData data;
		private final UUID camp, actor;
		private final Object member, binding;
		private final BlockPos store;
		private final Container inventory;
		private final BooleanSupplier identityAndPresence;
		private final boolean survival;
		private final SettlementSimulationState simulation;
		private final long modeVersion;
		private boolean used;

		private Ticket(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
			BooleanSupplier identityAndPresence) {
			this.data = data; this.camp = camp.id(); this.actor = actor;
			this.member = data.memberIdentity(actor); this.binding = data.stockpileBindingToken(camp);
			this.store = data.getStockpile(camp).orElseThrow(); this.inventory = inventory;
			this.identityAndPresence = identityAndPresence;
			this.survival = SettlementSimulationService.isSurvivalEnabled(data, camp);
			this.simulation = data.simulationOf(camp).orElse(null);
			this.modeVersion = simulation == null ? 0 : simulation.foodRequestModeVersion();
		}

		public BlockPos store() { return store; }
	}
}
