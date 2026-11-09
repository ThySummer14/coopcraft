package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationWorld;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Arrival-only intake of age-marked berry cargo; arrival and membership belong to the caller. */
public final class SettlementAgedFoodCargo {
	private static final Map<String, String> CONTEXT = Map.of("activity", "cargo_delivery");

	private SettlementAgedFoodCargo() { }

	/** Read-only cargo predicate. Destination capacity and core import capacity are checked on arrival. */
	public static boolean hasDeliverable(SettlementSimulationState state, Container inventory) {
		if (!enabled(state) || inventory == null) return false;
		UUID clock = state.foodClockId().orElseThrow();
		long day = state.world().day();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (deliverable(inventory.getItem(slot), clock, day) != null) return true;
		}
		return false;
	}

	/**
	 * Call only after actual arrival, on the simulation's owning thread, with distinct,
	 * non-overlapping standard containers and synchronous, non-reentrant mutation semantics.
	 * The chest is a temporary receiving-capacity gate: a detached whole-trip placement plan
	 * reserves each space once, then accepted cargo goes directly from the bag to camp batches.
	 * The chest is never written, including its pre-existing identical food. There is no new
	 * persistent chest food and no cap on the camp's total abstract stock.
	 * All expected validation failures precede writes; this is not crash-atomic or a transaction
	 * for arbitrary containers that throw or reenter during mutation.
	 */
	public static int deliver(SettlementSimulationState state, Container inventory, Container stockpile) {
		return deliver(state, inventory, stockpile, () -> true);
	}

	/** Rechecks the caller's live arrival and ownership authorization after the complete preflight. */
	public static int deliver(SettlementSimulationState state, Container inventory, Container stockpile,
		BooleanSupplier stillAuthorized) {
		if (!enabled(state) || inventory == null || stockpile == null || inventory == stockpile
			|| stillAuthorized == null) return 0;
		SimulationWorld world = state.world();
		UUID clock = state.foodClockId().orElseThrow();
		long day = world.day();
		long modeVersion = state.foodRequestModeVersion();
		List<ItemStack> bag = snapshot(inventory);
		List<ItemStack> store = snapshot(stockpile);
		List<ItemStack> reserved = new ArrayList<>(store);
		List<Intake> plan = new ArrayList<>();
		int accepted = 0;
		for (int sourceSlot = 0; sourceSlot < bag.size(); sourceSlot++) {
			ItemStack offered = bag.get(sourceSlot);
			SettlementFoodAge.Stamp age = deliverable(offered, clock, day);
			if (age == null) continue;
			int remaining = offered.getCount();
			for (int targetSlot = 0; targetSlot < reserved.size() && remaining > 0; targetSlot++) {
				ItemStack existing = reserved.get(targetSlot);
				if (!stockpile.canPlaceItem(targetSlot, offered)
					|| !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, offered)) continue;
				int capacity = stockpile.getMaxStackSize(existing.isEmpty() ? offered : existing) - existing.getCount();
				if (capacity <= 0) continue;
				int amount = Math.min(remaining, capacity);
				reserved.set(targetSlot, existing.isEmpty() ? offered.copyWithCount(amount)
					: existing.copyWithCount(existing.getCount() + amount));
				remaining -= amount;
			}
			int amount = offered.getCount() - remaining;
			if (amount == 0) continue;
			accepted = Math.addExact(accepted, amount);
			plan.add(new Intake(sourceSlot, offered.copyWithCount(remaining), amount, age.harvestDay()));
		}
		if (plan.isEmpty()) return 0;
		// No capacity is recycled after an import. Only these exact source-slot deltas can enter the camp.
		if (!sameModeAndDay(state, clock, day, modeVersion)) return 0;
		StorageOwner camp = state.campOwner();
		for (Intake intake : plan) {
			world.validateExternalFoodImport(ResourceType.WILD_FRUIT, intake.amount(), camp,
				intake.harvestDay(), List.of(), CONTEXT, List.of());
		}
		validateImportCapacity(world, plan.size());
		long nextBatchId = world.nextBatchId();
		var lastEvent = world.ledger().lastEvent();
		if (!stillAuthorized.getAsBoolean() || !unchanged(inventory, bag) || !unchanged(stockpile, store)
			|| !sameModeAndDay(state, clock, day, modeVersion) || world.nextBatchId() != nextBatchId
			|| !world.ledger().lastEvent().equals(lastEvent)) return 0;

		for (Intake intake : plan) {
			world.importExternalFoodBatch(ResourceType.WILD_FRUIT, intake.amount(), camp,
				intake.harvestDay(), List.of(), CONTEXT, List.of());
			inventory.setItem(intake.slot(), intake.remainder());
		}
		inventory.setChanged();
		return accepted;
	}

	private static boolean enabled(SettlementSimulationState state) {
		return state != null && state.survivalEnabled() && state.productionEnabled()
			&& state.foodClockId().isPresent();
	}

	private static boolean sameModeAndDay(SettlementSimulationState state, UUID clock, long day, long modeVersion) {
		return enabled(state) && state.foodRequestModeVersion() == modeVersion && state.world().day() == day
			&& state.foodClockId().filter(clock::equals).isPresent();
	}

	private static SettlementFoodAge.Stamp deliverable(ItemStack stack, UUID clock, long day) {
		if (stack == null || stack.isEmpty() || !stack.is(Items.SWEET_BERRIES)) return null;
		SettlementFoodAge.Stamp age = SettlementFoodAge.read(stack).orElse(null);
		return age != null && age.resourceType() == ResourceType.WILD_FRUIT && clock.equals(age.clockId())
			&& age.harvestDay() <= day && day <= age.expiryDay() ? age : null;
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) stacks.add(container.getItem(slot).copy());
		return stacks;
	}

	private static boolean unchanged(Container container, List<ItemStack> snapshot) {
		if (container.getContainerSize() != snapshot.size()) return false;
		for (int slot = 0; slot < snapshot.size(); slot++) {
			if (!ItemStack.matches(snapshot.get(slot), container.getItem(slot))) return false;
		}
		return true;
	}

	private static void validateImportCapacity(SimulationWorld world, int count) {
		try {
			Math.addExact(world.nextBatchId(), count);
			long lastEventId = world.ledger().lastEvent().map(event -> event.eventId()).orElse(0L);
			Math.addExact(Math.addExact(lastEventId, 1), count);
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Berry cargo exceeds representable batch or event IDs", exception);
		}
		if (world.nextBatchId() <= 0 || world.ledger().lastEvent().filter(event -> event.day() > world.day()).isPresent()) {
			throw new IllegalArgumentException("Berry cargo requires a valid simulation clock and next batch ID");
		}
	}

	private record Intake(int slot, ItemStack remainder, int amount, long harvestDay) { }
}
