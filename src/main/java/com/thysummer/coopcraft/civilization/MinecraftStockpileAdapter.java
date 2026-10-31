package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationWorld;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MinecraftStockpileAdapter {
	private static final Map<Item, ResourceType> ITEM_TYPES = Map.of(
		Items.SWEET_BERRIES, ResourceType.WILD_FRUIT,
		Items.POTATO, ResourceType.TUBERS,
		Items.COD, ResourceType.FISH,
		Items.BEEF, ResourceType.GAME,
		Items.OAK_LOG, ResourceType.WOOD,
		Items.COBBLESTONE, ResourceType.STONE
	);

	private MinecraftStockpileAdapter() {
	}

	public static Optional<ResourceType> resourceType(ItemStack stack) {
		if (stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(ITEM_TYPES.get(stack.getItem()));
	}

	public static int importToSimulation(
		Container container,
		SimulationWorld world,
		StorageOwner camp,
		ResourceType resourceType,
		int requested
	) {
		validateCamp(world, camp);
		if (requested <= 0) {
			throw new IllegalArgumentException("Import quantity must be positive");
		}
		int removed = removeSupportedItems(container, resourceType, requested);
		if (removed == 0) {
			return 0;
		}
		try {
			world.importExternalBatch(resourceType, removed, camp);
			return removed;
		} catch (RuntimeException exception) {
			int restored = SettlementStockpile.insert(container, new ItemStack(itemFor(resourceType), removed));
			if (restored != removed) {
				exception.addSuppressed(new IllegalStateException("Container rollback was incomplete"));
			}
			throw exception;
		}
	}

	public static int exportToContainer(
		SimulationWorld world,
		StorageOwner camp,
		String batchId,
		int requested,
		Container container
	) {
		validateCamp(world, camp);
		if (requested <= 0) {
			throw new IllegalArgumentException("Export quantity must be positive");
		}
		ResourceBatch batch = world.batch(batchId);
		if (!batch.holder().equals(camp)) {
			throw new IllegalArgumentException("Batch does not belong to the settlement camp");
		}
		ResourceType resourceType = batch.resourceType();
		int amount = (int) Math.min(requested, batch.quantity());
		int inserted = SettlementStockpile.insert(container, new ItemStack(itemFor(resourceType), amount));
		if (inserted == 0) {
			return 0;
		}
		try {
			world.exportExternalBatch(camp, batchId, inserted);
			return inserted;
		} catch (RuntimeException exception) {
			int removed = removeSupportedItems(container, resourceType, inserted);
			if (removed != inserted) {
				exception.addSuppressed(new IllegalStateException("Container rollback was incomplete"));
			}
			throw exception;
		}
	}

	private static int removeSupportedItems(Container container, ResourceType resourceType, int requested) {
		int remaining = requested;
		int removedTotal = 0;
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = container.getItem(slot);
			if (resourceType(stack).orElse(null) != resourceType) {
				continue;
			}
			ItemStack removed = container.removeItem(slot, Math.min(stack.getCount(), remaining));
			remaining -= removed.getCount();
			removedTotal += removed.getCount();
		}
		if (removedTotal > 0) {
			container.setChanged();
		}
		return removedTotal;
	}

	private static void validateCamp(SimulationWorld world, StorageOwner camp) {
		if (camp.scope() != StorageOwner.Scope.CAMP) {
			throw new IllegalArgumentException("Minecraft handoff requires a camp storage owner");
		}
		world.camp(camp.id());
	}

	private static Item itemFor(ResourceType resourceType) {
		return switch (resourceType) {
			case WILD_FRUIT -> Items.SWEET_BERRIES;
			case TUBERS -> Items.POTATO;
			case FISH -> Items.COD;
			case GAME -> Items.BEEF;
			case WOOD -> Items.OAK_LOG;
			case STONE -> Items.COBBLESTONE;
		};
	}
}
