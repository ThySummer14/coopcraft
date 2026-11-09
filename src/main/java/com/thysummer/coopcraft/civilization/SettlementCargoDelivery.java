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

	public enum Status { INVALID, NOT_AT_STORE, NO_CAPACITY, DELIVERED }
	public record Result(Status status, int moved) { }

	public static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor,
		Container inventory, BooleanSupplier identityAndPresence) {
		if (data == null || camp == null || actor == null || inventory == null || identityAndPresence == null
			|| !data.isMember(camp, actor) || data.getStockpile(camp).isEmpty()
			|| !identityAndPresence.getAsBoolean()) return null;
		return new Ticket(data, camp, actor, inventory, identityAndPresence);
	}

	/** Lightweight per-tick identity check; deliberately does not inspect either inventory. */
	public static boolean valid(Ticket ticket, SettlementSavedData data, Settlement camp, UUID actor,
		Container inventory) {
		return ticket != null && !ticket.used && matches(ticket, data, camp, actor, inventory);
	}

	private static boolean matches(Ticket ticket, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		return data == ticket.data && camp != null
			&& ticket.camp.equals(camp.id()) && ticket.actor.equals(actor) && inventory == ticket.inventory
			&& data.isMember(camp, actor) && ticket.member == data.memberIdentity(actor)
			&& ticket.binding == data.stockpileBindingToken(camp)
			&& data.getStockpile(camp).filter(ticket.store::equals).isPresent()
			&& ticket.identityAndPresence.getAsBoolean();
	}

	public static Result finish(Ticket ticket, SettlementSavedData data, Settlement camp, UUID actor,
		Container inventory, Container currentStore, BooleanSupplier atStore) {
		if (ticket == null || ticket.used) return new Result(Status.INVALID, 0);
		// Every final attempt is consumed, including failed identity or arrival checks.
		ticket.used = true;
		if (!matches(ticket, data, camp, actor, inventory) || currentStore == null || currentStore == inventory || atStore == null) return new Result(Status.INVALID, 0);
		if (!atStore.getAsBoolean()) return new Result(Status.NOT_AT_STORE, 0);
		if (!matches(ticket, data, camp, actor, inventory)) return new Result(Status.INVALID, 0);
		int moved = SettlementStockpile.depositGatheredResources(currentStore, inventory);
		return new Result(moved > 0 ? Status.DELIVERED : Status.NO_CAPACITY, moved);
	}

	public static final class Ticket {
		private final SettlementSavedData data;
		private final UUID camp, actor;
		private final Object member, binding;
		private final BlockPos store;
		private final Container inventory;
		private final BooleanSupplier identityAndPresence;
		private boolean used;

		private Ticket(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
			BooleanSupplier identityAndPresence) {
			this.data = data; this.camp = camp.id(); this.actor = actor;
			this.member = data.memberIdentity(actor); this.binding = data.stockpileBindingToken(camp);
			this.store = data.getStockpile(camp).orElseThrow(); this.inventory = inventory;
			this.identityAndPresence = identityAndPresence;
		}

		public BlockPos store() { return store; }
	}
}
