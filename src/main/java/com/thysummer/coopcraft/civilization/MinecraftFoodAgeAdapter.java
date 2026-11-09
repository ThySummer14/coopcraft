package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationWorld;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Explicit food handoffs on the simulation's owning thread, using its observed day clock.
 * Plans use detached stacks and validate all world changes before touching either store.
 * Commit relies on standard synchronous Container mutations without reentrant inventory edits;
 * no rollback by item type is attempted, since that could take unrelated existing stacks.
 */
public final class MinecraftFoodAgeAdapter {
	private MinecraftFoodAgeAdapter() {
	}

	public static int importToSimulation(
		Container container,
		SettlementSimulationState state,
		ResourceType resourceType,
		int requested
	) {
		validateRequest(resourceType, requested);
		SimulationWorld world = state.world();
		StorageOwner camp = state.campOwner();
		validateCamp(world, camp);
		UUID clock = state.foodClockId().orElse(null);
		if (clock == null) return 0;

		List<Intake> plan = new ArrayList<>();
		int accepted = 0;
		for (int slot = 0; slot < container.getContainerSize() && accepted < requested; slot++) {
			ItemStack original = container.getItem(slot).copy();
			long harvestDay;
			Optional<SettlementFoodAge.Stamp> marked = SettlementFoodAge.read(original);
			if (marked.isPresent()) {
				SettlementFoodAge.Stamp stamp = marked.get();
				if (!clock.equals(stamp.clockId()) || stamp.resourceType() != resourceType
					|| stamp.harvestDay() > world.day() || world.day() > stamp.expiryDay()) continue;
				harvestDay = stamp.harvestDay();
			} else {
				if (SettlementFoodAge.isMarked(original)
					|| SettlementFoodAge.ordinaryFoodType(original).orElse(null) != resourceType) continue;
				// An explicit legacy intake has no known past age. Only an unmodified plain stack qualifies.
				harvestDay = world.day();
			}
			int amount = Math.min(original.getCount(), requested - accepted);
			ItemStack remainder = original.copyWithCount(original.getCount() - amount);
			world.validateExternalFoodImport(resourceType, amount, camp, harvestDay, List.of(), Map.of(), List.of());
			plan.add(new Intake(slot, remainder, amount, harvestDay));
			accepted += amount;
		}
		if (plan.isEmpty()) return 0;
		validateImportCapacity(world, plan.size());

		// Each source slot gets its own batch, retaining its age even beside fresher identical food.
		for (Intake intake : plan) {
			world.importExternalFoodBatch(resourceType, intake.amount(), camp, intake.harvestDay(),
				List.of(), Map.of(), List.of());
			container.setItem(intake.slot(), intake.remainder());
		}
		container.setChanged();
		return accepted;
	}

	public static int exportToContainer(
		SettlementSimulationState state,
		String batchId,
		int requested,
		Container container
	) {
		if (requested <= 0) throw new IllegalArgumentException("Export quantity must be positive");
		SimulationWorld world = state.world();
		StorageOwner camp = state.campOwner();
		validateCamp(world, camp);
		UUID clock = state.foodClockId().orElse(null);
		if (clock == null) return 0;
		ResourceBatch batch = world.batch(batchId);
		if (!batch.holder().equals(camp)) {
			throw new IllegalArgumentException("Batch does not belong to the settlement camp");
		}
		validateRequest(batch.resourceType(), requested);
		if (batch.harvestDay() > world.day() || world.day() > batch.expiryDay()
			|| batch.nutritionPerUnit() != batch.resourceType().nutritionPerUnit()) return 0;
		int amount = (int) Math.min(requested, batch.quantity());
		ItemStack offered = SettlementFoodAge.stamp(new ItemStack(itemFor(batch.resourceType()), amount),
			clock, batch.harvestDay());
		// A custom initial/restored expiry cannot be represented by this bounded format without changing age.
		if (SettlementFoodAge.read(offered).orElseThrow().expiryDay() != batch.expiryDay()) return 0;

		List<Placement> plan = new ArrayList<>();
		int accepted = 0;
		for (int slot = 0; slot < container.getContainerSize() && accepted < amount; slot++) {
			ItemStack existing = container.getItem(slot).copy();
			if (!container.canPlaceItem(slot, offered)) continue;
			if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, offered)) continue;
			int capacity = container.getMaxStackSize(existing.isEmpty() ? offered : existing) - existing.getCount();
			if (capacity <= 0) continue;
			int inserted = Math.min(amount - accepted, capacity);
			ItemStack replacement = existing.isEmpty() ? offered.copyWithCount(inserted)
				: existing.copyWithCount(existing.getCount() + inserted);
			plan.add(new Placement(slot, replacement));
			accepted += inserted;
		}
		if (accepted == 0) return 0;
		validateExport(world, batch, accepted);

		world.exportExternalBatch(camp, batchId, accepted, List.of(), Map.of());
		for (Placement placement : plan) container.setItem(placement.slot(), placement.stack());
		container.setChanged();
		return accepted;
	}

	private static void validateRequest(ResourceType type, int requested) {
		if (requested <= 0 || type == null || !type.isFood()) {
			throw new IllegalArgumentException("Food handoff requires a supported food and positive quantity");
		}
	}

	private static void validateCamp(SimulationWorld world, StorageOwner camp) {
		if (camp.scope() != StorageOwner.Scope.CAMP) {
			throw new IllegalArgumentException("Food handoff requires a camp storage owner");
		}
		world.camp(camp.id());
	}

	private static void validateImportCapacity(SimulationWorld world, int batchCount) {
		try {
			Math.addExact(world.nextBatchId(), batchCount);
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Food intake exceeds representable batch IDs", exception);
		}
		validateEventCapacity(world, batchCount);
	}

	private static void validateExport(SimulationWorld world, ResourceBatch batch, int quantity) {
		validateEventCapacity(world, 1);
		if (quantity <= 0 || quantity > batch.quantity() || world.ledger().event(batch.lastEventId()).isEmpty()) {
			throw new IllegalArgumentException("Food export requires available stock and its recorded cause");
		}
		Math.multiplyExact((long) quantity, batch.nutritionPerUnit());
	}

	private static void validateEventCapacity(SimulationWorld world, int eventCount) {
		try {
			long lastEventId = world.ledger().lastEvent().map(event -> event.eventId()).orElse(0L);
			Math.addExact(Math.addExact(lastEventId, 1), eventCount);
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Food handoff exceeds representable event IDs", exception);
		}
	}

	private static Item itemFor(ResourceType type) {
		return switch (type) {
			case WILD_FRUIT -> Items.SWEET_BERRIES;
			case TUBERS -> Items.POTATO;
			case FISH -> Items.COD;
			case GAME -> Items.BEEF;
			default -> throw new IllegalArgumentException("Unsupported physical food");
		};
	}

	private record Intake(int slot, ItemStack remainder, int amount, long harvestDay) {
	}

	private record Placement(int slot, ItemStack stack) {
	}
}
