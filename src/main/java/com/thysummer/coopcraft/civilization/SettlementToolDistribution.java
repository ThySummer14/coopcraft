package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One actual public stone pickaxe to an empty hand. No crafting, loan, metadata or virtual stock.
 * Server-thread only: distinct storage/hand, synchronous non-reentrant container mutation,
 * side-effect-free live reads and reference-retaining setters, as in SettlementShearsLoan.
 * Before/after mutation exceptions are resolved by actual custody, never replacement items.
 * Cloning/discarding setters, failing rollback and crash atomicity are outside this contract.
 */
final class SettlementToolDistribution {
	private SettlementToolDistribution() { }

	/** Live evidence only, not a reservation: competing tickets may pin the same stack. */
	static final class Ticket {
		private final SettlementSavedData data;
		private final Settlement camp;
		private final UUID actor;
		private final Object member, storeToken;
		private final BlockPos store;
		private final SettlementSimulationState state;
		private final long modeVersion;
		private final Container backpack, stockpile;
		private final SettlementShearsLoan.Hand mainHand;
		private final Supplier<ItemStack> offHand;
		private final BooleanSupplier worldValid, demand;
		private final int slot;
		private final ItemStack actual, snapshot;
		private boolean used, cancelled, checking;

		private Ticket(SettlementSavedData data, Settlement camp, UUID actor, Container backpack,
			Container stockpile, SettlementShearsLoan.Hand mainHand, Supplier<ItemStack> offHand,
			BooleanSupplier worldValid, BooleanSupplier demand, int slot) {
			this.data = data; this.camp = camp; this.actor = actor;
			this.backpack = backpack; this.stockpile = stockpile;
			this.mainHand = mainHand; this.offHand = offHand;
			this.worldValid = worldValid; this.demand = demand; this.slot = slot;
			member = data.memberIdentity(actor); storeToken = data.stockpileBindingToken(camp);
			store = data.getStockpile(camp).orElseThrow();
			state = data.simulationOf(camp).orElse(null);
			modeVersion = state == null ? 0 : state.foodRequestModeVersion();
			actual = stockpile.getItem(slot); snapshot = actual.copy();
		}
		BlockPos store() { return store; }
	}

	/** Read-only. World/demand suppliers must be live, read-only checks; no inventory is reserved. */
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, Container backpack,
		Container stockpile, SettlementShearsLoan.Hand mainHand, Supplier<ItemStack> offHand,
		BooleanSupplier worldValid, BooleanSupplier demand) {
		if (data == null || camp == null || actor == null || backpack == null || stockpile == null
			|| backpack == stockpile || mainHand == null || offHand == null || worldValid == null || demand == null
			|| !data.canonicalSettlement(camp) || !data.isMember(camp, actor) || data.getStockpile(camp).isEmpty()) return null;
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			if (!ordinary(stockpile.getItem(slot))) continue;
			Ticket t = new Ticket(data, camp, actor, backpack, stockpile, mainHand, offHand, worldValid, demand, slot);
			return valid(t, data, camp, actor, backpack) ? t : null;
		}
		return null;
	}

	static boolean valid(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container backpack) {
		if (t == null || t.used || t.cancelled || t.checking || !identity(t, data, camp, actor, backpack)) return false;
		t.checking = true;
		try {
			return t.worldValid.getAsBoolean() && t.demand.getAsBoolean()
				&& !t.used && !t.cancelled && identity(t, data, camp, actor, backpack) && available(t);
		} finally { t.checking = false; }
	}

	private static boolean identity(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container backpack) {
		return t.data == data && t.camp == camp && t.actor.equals(actor) && t.backpack == backpack
			&& data.canonicalSettlement(camp) && data.isMember(camp, actor)
			&& t.member == data.memberIdentity(actor) && t.storeToken == data.stockpileBindingToken(camp)
			&& data.getStockpile(camp).filter(t.store::equals).isPresent()
			&& data.simulationOf(camp).orElse(null) == t.state
			&& (t.state == null || t.state.foodRequestModeVersion() == t.modeVersion);
	}

	private static boolean ordinary(ItemStack stack) {
		return stack != null && stack.is(Items.STONE_PICKAXE) && stack.getCount() == 1
			&& stack.getComponentsPatch().isEmpty();
	}

	private static boolean available(Ticket t) {
		return t.slot < t.stockpile.getContainerSize() && t.stockpile.getItem(t.slot) == t.actual
			&& ordinary(t.actual) && ItemStack.matches(t.snapshot, t.actual)
			&& SettlementWorkbenchToolEquipment.needsFirstPickaxe(t.backpack, t.mainHand.get(), t.offHand.get());
	}

	/** Exactly one attempt, including failed arrival, stale authority, callback cancellation or exceptions. */
	static boolean finish(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container backpack,
		BooleanSupplier atStore) {
		if (t == null) return false;
		if (t.used || t.cancelled || t.checking) { cancel(t); return false; }
		t.used = true;
		if (atStore == null || !identity(t, data, camp, actor, backpack)) return false;
		// All extensible gates precede the final authority/custody checks. Reentry consumes/cancels the ticket.
		t.checking = true;
		try {
			if (!t.worldValid.getAsBoolean() || !t.demand.getAsBoolean() || !atStore.getAsBoolean()
				|| t.cancelled || !identity(t, data, camp, actor, backpack) || !available(t)) return false;
		} finally { t.checking = false; }
		try { t.stockpile.removeItemNoUpdate(t.slot); }
		catch (RuntimeException mutationFailure) { /* Inspect custody, never repeat removal. */ }
		if (t.stockpile.getItem(t.slot) == t.actual) return false;
		if (!t.stockpile.getItem(t.slot).isEmpty())
			throw new IllegalStateException("Public tool source did not detach its actual stack");
		boolean delivered = false;
		try {
			try { t.mainHand.set(t.actual); }
			catch (RuntimeException mutationFailure) { /* Setter may have committed before throwing. */ }
			delivered = t.mainHand.get() == t.actual;
			return delivered;
		} finally {
			if (!delivered) {
				if (!t.stockpile.getItem(t.slot).isEmpty())
					throw new IllegalStateException("Cannot restore public tool to occupied source");
				try { t.stockpile.setItem(t.slot, t.actual); }
				catch (RuntimeException mutationFailure) { /* Inspect actual restoration. */ }
				if (t.stockpile.getItem(t.slot) != t.actual)
					throw new IllegalStateException("Cannot restore actual public tool");
			}
			try { t.stockpile.setChanged(); }
			catch (RuntimeException notificationFailure) { /* Custody already committed. */ }
		}
	}

	static void cancel(Ticket t) { if (t != null) { t.used = true; t.cancelled = true; } }
}
