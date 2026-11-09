package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Demand-driven, single-batch production. Only the real communal container holds products. */
public final class SettlementMaterialCrafting {
	public enum Recipe {
		PLANKS(Items.OAK_PLANKS, 4, Items.OAK_LOG, 1),
		CRAFTING_TABLE(Items.CRAFTING_TABLE, 1, Items.OAK_PLANKS, 4),
		STICKS(Items.STICK, 8, Items.OAK_LOG, 1),
		DOOR(Items.OAK_DOOR, 3, Items.OAK_PLANKS, 6),
		WOOL(Items.WHITE_WOOL, 1, Items.STRING, 4),
		BED(Items.WHITE_BED, 1, Items.WHITE_WOOL, 3, Items.OAK_PLANKS, 3),
		TORCH_COAL(Items.TORCH, 4, Items.COAL, 1, Items.STICK, 1),
		TORCH_CHARCOAL(Items.TORCH, 4, Items.CHARCOAL, 1, Items.STICK, 1),
		CAMPFIRE_COAL(Items.CAMPFIRE, 1, Items.OAK_LOG, 3, Items.STICK, 3, Items.COAL, 1),
		CAMPFIRE_CHARCOAL(Items.CAMPFIRE, 1, Items.OAK_LOG, 3, Items.STICK, 3, Items.CHARCOAL, 1);

		private final Item output;
		private final int count;
		private final Map<Item, Integer> ingredients;

		Recipe(Item output, int count, Object... inputs) {
			this.output = output;
			this.count = count;
			Map<Item, Integer> ingredients = new LinkedHashMap<>();
			for (int i = 0; i < inputs.length; i += 2) {
				ingredients.put((Item) inputs[i], (Integer) inputs[i + 1]);
			}
			this.ingredients = java.util.Collections.unmodifiableMap(ingredients);
		}

		public String id() { return "material_" + name().toLowerCase(java.util.Locale.ROOT); }
		public Item output() { return output; }
		public int count() { return count; }
		public Map<Item, Integer> ingredients() { return ingredients; }
	}

	private SettlementMaterialCrafting() { }

	/** Demand is remaining construction consumption, before subtracting real stock. Iteration order is priority. */
	public static Optional<Recipe> chooseRecipe(Container stockpile, SettlementSimulationState state,
		Map<Item, Integer> demand) {
		if (stockpile == null) { return Optional.empty(); }
		for (Map.Entry<Item, Integer> need : demand.entrySet()) {
			if (need.getValue() <= availableProduct(stockpile, state, need.getKey())) {
				continue;
			}
			for (Recipe recipe : Recipe.values()) {
				if (recipe != Recipe.WOOL && recipe.output() == need.getKey()
					&& preview(stockpile, state, recipe) != null) {
					return Optional.of(recipe);
				}
			}
			// Explicit raw-input intermediates keep dependency depth bounded at one.
			for (Recipe recipe : Recipe.values()) {
				if (recipe.output() != need.getKey()) { continue; }
				for (Recipe prerequisite : List.of(Recipe.PLANKS, Recipe.STICKS)) {
					if (recipe.ingredients().getOrDefault(prerequisite.output(), 0)
						> countPlain(stockpile, prerequisite.output())
						&& preview(stockpile, state, prerequisite) != null) {
						return Optional.of(prerequisite);
					}
				}
				// Wool is only a bed prerequisite, capped at the next bed's three plain wool.
				if (recipe == Recipe.BED
					&& countPlain(stockpile, Items.WHITE_WOOL) < recipe.ingredients().get(Items.WHITE_WOOL)
					&& preview(stockpile, state, Recipe.WOOL) != null) {
					return Optional.of(Recipe.WOOL);
				}
			}
		}
		return Optional.empty();
	}

	private static long availableProduct(Container stockpile, SettlementSimulationState state, Item item) {
		// Construction consumes named beds as well; other crafting stock keeps its plain-item policy.
		return item == Items.WHITE_BED ? ConstructionMaterials.available(stockpile, state, item)
			: countPlain(stockpile, item);
	}

	/** Read-only gathering priority: structural logs need no workshop and must be publicly consumable. */
	static boolean needsConstructionLogs(Container stockpile, Container actorInventory,
		SettlementSimulationState state, List<Map<Item, Integer>> demands, boolean hasCraftingStation) {
		if (stockpile == null || demands.isEmpty()) { return false; }
		long structuralLogs = ConstructionMaterials.available(stockpile, state, Items.OAK_LOG);
		for (Map<Item, Integer> demand : demands) {
			if (demand.getOrDefault(Items.OAK_LOG, 0) > structuralLogs) { return true; }
		}
		if (!hasCraftingStation) { return false; }
		// Preserve the one-batch crafting shortage rule, including carried plain intermediates.
		long logs = countPlain(actorInventory, Items.OAK_LOG)
			+ availablePlain(stockpile, state, Items.OAK_LOG);
		long availableSticks = countPlain(actorInventory, Items.STICK)
			+ availablePlain(stockpile, state, Items.STICK);
		for (Map<Item, Integer> demand : demands) {
			for (Recipe recipe : Recipe.values()) {
				if (demand.getOrDefault(recipe.output(), 0) <= availableProduct(stockpile, state, recipe.output())) {
					continue;
				}
				int planks = Math.max(0, recipe.ingredients().getOrDefault(Items.OAK_PLANKS, 0)
					- countPlain(stockpile, Items.OAK_PLANKS));
				long sticks = Math.max(0L, recipe.ingredients().getOrDefault(Items.STICK, 0) - availableSticks);
				long requiredLogs = recipe.ingredients().getOrDefault(Items.OAK_LOG, 0)
					+ (planks + 3) / 4 + (sticks + 7) / 8;
				if (logs < requiredLogs) {
					return true;
				}
			}
		}
		return false;
	}

	/** Call on the server thread. Failed exports may leave real ingredients, never a half-consumed recipe. */
	public static boolean craft(Container stockpile, SettlementSimulationState state, Recipe recipe,
		UUID actor, Runnable dirtyCallback) {
		if (stockpile == null || preview(stockpile, state, recipe) == null) {
			return false;
		}
		for (Map.Entry<Item, Integer> input : recipe.ingredients().entrySet()) {
			int missing = input.getValue() - countPlain(stockpile, input.getKey());
			if (missing <= 0) { continue; }
			if (!canExport(state)) { return false; }
			ResourceType type = MinecraftStockpileAdapter.resourceType(new ItemStack(input.getKey())).orElse(null);
			if (type == null) { return false; }
			List<String> actors = state.residentId(actor).map(List::of).orElse(List.of());
			for (ResourceBatch batch : state.world().batchesAt(state.campOwner())) {
				if (missing <= 0) { break; }
				if (batch.resourceType() != type) { continue; }
				if (!canExport(state)) { return false; }
				int exported = MinecraftStockpileAdapter.exportToContainer(state.world(), state.campOwner(),
					batch.batchId(), missing, stockpile, actors,
					Map.of("source", "minecraft_material_crafting", "activity", "craft",
						"recipe", recipe.id(), "villagerId", actor.toString()));
				if (exported > 0) { dirtyCallback.run(); }
				missing -= exported;
			}
			if (missing > 0) { return false; }
		}
		// Work on a copy even after export: restrictions/capacity may have changed during handoff.
		SimpleContainer result = copy(stockpile);
		if (!consumeAndProduce(result, recipe)) { return false; }
		for (int slot = 0; slot < result.getContainerSize(); slot++) {
			stockpile.setItem(slot, result.getItem(slot).copy());
		}
		stockpile.setChanged();
		dirtyCallback.run();
		return true;
	}

	private static boolean canExport(SettlementSimulationState state) {
		return state != null && state.survivalEnabled() && state.productionEnabled();
	}

	private static SimpleContainer preview(Container stockpile, SettlementSimulationState state, Recipe recipe) {
		SimpleContainer result = copy(stockpile);
		for (Map.Entry<Item, Integer> input : recipe.ingredients().entrySet()) {
			int missing = input.getValue() - countPlain(result, input.getKey());
			if (missing <= 0) { continue; }
			if (!canExport(state)) { return null; }
			ResourceType type = MinecraftStockpileAdapter.resourceType(new ItemStack(input.getKey())).orElse(null);
			if (type == null) { return null; }
			long available = state.world().batchesAt(state.campOwner()).stream()
				.filter(batch -> batch.resourceType() == type).mapToLong(ResourceBatch::quantity).sum();
			if (available < missing || SettlementStockpile.insert(result, new ItemStack(input.getKey(), missing)) != missing) {
				return null;
			}
		}
		return consumeAndProduce(result, recipe) ? result : null;
	}

	private static boolean consumeAndProduce(Container container, Recipe recipe) {
		for (Map.Entry<Item, Integer> input : recipe.ingredients().entrySet()) {
			if (countPlain(container, input.getKey()) < input.getValue()) { return false; }
		}
		for (Map.Entry<Item, Integer> input : recipe.ingredients().entrySet()) {
			int remaining = input.getValue();
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (plain(stack, input.getKey())) {
					remaining -= container.removeItem(slot, Math.min(remaining, stack.getCount())).getCount();
				}
			}
		}
		return SettlementStockpile.insert(container, new ItemStack(recipe.output(), recipe.count())) == recipe.count();
	}

	static long availablePlain(Container stockpile, SettlementSimulationState state, Item item) {
		long count = stockpile == null ? 0 : countPlain(stockpile, item);
		if (state == null || !state.survivalEnabled() || !state.productionEnabled()) {
			return count;
		}
		ResourceType type = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		return type == null ? count : count + state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == type).mapToLong(ResourceBatch::quantity).sum();
	}

	static int countPlain(Container container, Item item) {
		int count = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (plain(stack, item)) { count += stack.getCount(); }
		}
		return count;
	}

	private static boolean plain(ItemStack stack, Item item) {
		return !stack.isEmpty() && stack.is(item) && stack.getComponentsPatch().isEmpty();
	}

	private static SimpleContainer copy(Container source) {
		SimpleContainer copy = new SimpleContainer(source.getContainerSize()) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) { return source.canPlaceItem(slot, stack); }
			@Override
			public int getMaxStackSize() { return source.getMaxStackSize(); }
			@Override
			public int getMaxStackSize(ItemStack stack) { return source.getMaxStackSize(stack); }
		};
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			copy.setItem(slot, source.getItem(slot).copy());
		}
		return copy;
	}
}
