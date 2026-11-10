package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** A single real ration changes hands. Server-thread, standard non-reentrant SimpleContainer contract. */
final class SettlementFoodSharing {
	private SettlementFoodSharing() { }

	static final class Ticket {
		final SettlementSavedData data;
		final Settlement camp;
		final UUID giver, recipient;
		final SimpleContainer giverBag, recipientBag;
		final Object giverMember, recipientMember;
		final MemberNeeds giverNeeds, recipientNeeds;
		final SettlementSimulationState simulation;
		final long mode;
		final ItemStack[] giverIdentities, giverSnapshots, recipientIdentities, recipientSnapshots;
		final int source, destination;
		final BooleanSupplier presence;
		boolean used, checking;
		private Ticket(SettlementSavedData data, Settlement camp, UUID giver, SimpleContainer giverBag,
			UUID recipient, SimpleContainer recipientBag, int source, int destination, BooleanSupplier presence) {
			this.data = data; this.camp = camp; this.giver = giver; this.giverBag = giverBag;
			this.recipient = recipient; this.recipientBag = recipientBag; this.source = source;
			this.destination = destination; this.presence = presence;
			giverMember = data.memberIdentity(giver); recipientMember = data.memberIdentity(recipient);
			giverNeeds = data.needsOf(camp, giver); recipientNeeds = data.needsOf(camp, recipient);
			simulation = data.simulationOf(camp).orElse(null); mode = simulation == null ? 0 : simulation.foodRequestModeVersion();
			giverIdentities = identities(giverBag); recipientIdentities = identities(recipientBag);
			giverSnapshots = copies(giverIdentities); recipientSnapshots = copies(recipientIdentities);
		}
	}
	private static ItemStack[] identities(SimpleContainer bag) {
		ItemStack[] stacks = new ItemStack[bag.getContainerSize()];
		for (int slot = 0; slot < stacks.length; slot++) stacks[slot] = bag.getItem(slot);
		return stacks;
	}
	private static ItemStack[] copies(ItemStack[] stacks) {
		ItemStack[] copies = new ItemStack[stacks.length];
		for (int slot = 0; slot < stacks.length; slot++) copies[slot] = stacks[slot].copy();
		return copies;
	}
	static long ordinaryFoodPoints(SimpleContainer bag) {
		long points = 0;
		for (int slot = 0; slot < bag.getContainerSize(); slot++) {
			ItemStack stack = bag.getItem(slot);
			if (SettlementPersonalMeal.ordinary(stack)) points += (long) stack.getCount() * SettlementFood.points(stack.getItem());
		}
		return points;
	}
	private static boolean eligible(SettlementSavedData data, Settlement camp, UUID giver, SimpleContainer giverBag,
		UUID recipient, SimpleContainer recipientBag) {
		return data != null && camp != null && giver != null && recipient != null && !giver.equals(recipient)
			&& giverBag != null && recipientBag != null && giverBag != recipientBag
			&& data.canonicalSettlement(camp) && data.isMember(camp, giver) && data.isMember(camp, recipient)
			&& !SettlementSimulationService.isSurvivalEnabled(data, camp)
			&& data.needsOf(camp, giver).satiety() > SettlementNeeds.HUNGRY_AT
			&& data.needsOf(camp, recipient).satiety() <= SettlementNeeds.HUNGRY_AT
			&& ordinaryFoodPoints(recipientBag) == 0;
	}
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID giver, SimpleContainer giverBag,
		UUID recipient, SimpleContainer recipientBag, BooleanSupplier presence) {
		if (presence == null || !eligible(data, camp, giver, giverBag, recipient, recipientBag)) return null;
		int destination = -1;
		for (int slot = 0; slot < recipientBag.getContainerSize(); slot++) if (recipientBag.getItem(slot).isEmpty()) { destination = slot; break; }
		if (destination < 0) return null;
		long spare = ordinaryFoodPoints(giverBag) - SettlementFoodCargo.retainedFoodPoints(data, camp, giver);
		for (int slot = 0; slot < giverBag.getContainerSize(); slot++) {
			ItemStack stack = giverBag.getItem(slot);
			if (SettlementPersonalMeal.ordinary(stack) && spare >= SettlementFood.points(stack.getItem())) {
				Ticket t = new Ticket(data, camp, giver, giverBag, recipient, recipientBag, slot, destination, presence);
				return valid(t) ? t : null;
			}
		}
		return null;
	}
	private static boolean unchanged(SimpleContainer bag, ItemStack[] identities, ItemStack[] snapshots) {
		if (bag.getContainerSize() != identities.length) return false;
		for (int slot = 0; slot < identities.length; slot++)
			if (bag.getItem(slot) != identities[slot] || !ItemStack.matches(bag.getItem(slot), snapshots[slot])) return false;
		return true;
	}
	private static boolean unchanged(Ticket t) {
		return !t.used && t.giverMember != null && t.recipientMember != null
			&& t.giverMember == t.data.memberIdentity(t.giver) && t.recipientMember == t.data.memberIdentity(t.recipient)
			&& t.simulation == t.data.simulationOf(t.camp).orElse(null)
			&& (t.simulation == null || t.mode == t.simulation.foodRequestModeVersion())
			&& t.giverNeeds == t.data.needsOf(t.camp, t.giver) && t.recipientNeeds == t.data.needsOf(t.camp, t.recipient)
			&& eligible(t.data, t.camp, t.giver, t.giverBag, t.recipient, t.recipientBag)
			&& unchanged(t.giverBag, t.giverIdentities, t.giverSnapshots)
			&& unchanged(t.recipientBag, t.recipientIdentities, t.recipientSnapshots)
			&& SettlementPersonalMeal.ordinary(t.giverBag.getItem(t.source)) && t.recipientBag.getItem(t.destination).isEmpty()
			&& ordinaryFoodPoints(t.giverBag) - SettlementFood.points(t.giverBag.getItem(t.source).getItem())
				>= SettlementFoodCargo.retainedFoodPoints(t.data, t.camp, t.giver);
	}
	static boolean valid(Ticket t) {
		if (t == null || t.checking) return false;
		t.checking = true;
		boolean valid = false;
		try { valid = unchanged(t) && t.presence.getAsBoolean() && unchanged(t); return valid; }
		finally { if (!valid) t.used = true; t.checking = false; }
	}
	static void cancel(Ticket t) { if (t != null) t.used = true; }
	/** All callbacks run before debit. No rollback copies, hunger changes, or abstract inventory writes. */
	static boolean finish(Ticket t, BooleanSupplier arrival) {
		if (t == null || t.checking) return false;
		t.checking = true;
		try {
			if (arrival == null || !unchanged(t) || !t.presence.getAsBoolean() || !unchanged(t)
				|| !arrival.getAsBoolean() || !unchanged(t) || !t.presence.getAsBoolean() || !unchanged(t)) return false;
			ItemStack gift = t.giverIdentities[t.source].copyWithCount(1);
			t.used = true;
			t.giverIdentities[t.source].shrink(1);
			t.recipientBag.setItem(t.destination, gift);
			t.giverBag.setChanged();
			return true;
		} finally { t.used = true; t.checking = false; }
	}
}
