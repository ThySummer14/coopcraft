package com.thysummer.coopcraft.civilization;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.SettlementMaterialCrafting.Recipe;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-thread-only, single-use supported workbench batches. Tickets never own items or survive reload. */
final class SettlementWorkbenchCrafting {
	private SettlementWorkbenchCrafting() { }

	static final class Ticket {
		private final SettlementSavedData data;
		private final Settlement camp;
		private final UUID actor;
		private final Container inventory;
		private final Recipe recipe;
		private final Object member, storeToken, stationToken;
		private final BlockPos store, station;
		private final SettlementSimulationState state;
		private final long modeVersion;
		private final BooleanSupplier worldValid, demand;
		private final UUID batch = UUID.randomUUID();
		private boolean collected, used, finished, equipmentAttempted;
		private SettlementSimulationService.CraftingAttempt attempt;

		private Ticket(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
			Recipe recipe, BooleanSupplier worldValid, BooleanSupplier demand) {
			this.recipe = recipe;
			this.data = data;
			this.camp = camp;
			this.actor = actor;
			this.inventory = inventory;
			this.worldValid = worldValid;
			this.demand = demand;
			member = data.memberIdentity(actor);
			storeToken = data.stockpileBindingToken(camp);
			stationToken = stationToken(data, camp, recipe);
			store = data.getStockpile(camp).orElseThrow();
			station = SettlementWorkbenchCrafting.station(data, camp, recipe).orElseThrow();
			state = data.simulationOf(camp).orElse(null);
			modeVersion = state == null ? 0 : state.foodRequestModeVersion();
		}
		BlockPos store() { return store; }
		BlockPos station() { return station; }
		boolean collected() { return collected; }
		Recipe recipe() { return recipe; }
	}

	private static java.util.Optional<BlockPos> station(SettlementSavedData data, Settlement camp, Recipe recipe) {
		return recipe == Recipe.SMOOTH_STONE ? data.getGrindingStation(camp) : data.getCraftingStation(camp);
	}

	private static Object stationToken(SettlementSavedData data, Settlement camp, Recipe recipe) {
		return recipe == Recipe.SMOOTH_STONE ? data.grindingStationBindingToken(camp) : data.craftingStationBindingToken(camp);
	}

	/** Read-only: neither reserves supplies nor starts a provenance attempt. */
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
		BooleanSupplier worldValid, BooleanSupplier demandStillNeeded) {
		return prepare(data, camp, actor, inventory, Recipe.PLANKS, worldValid, demandStillNeeded);
	}

	/** Demand checks remaining product need, never whether the withdrawn ingredients remain in the store. */
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
		Recipe recipe, BooleanSupplier worldValid, BooleanSupplier demandStillNeeded) {
		if (!SettlementWorkbenchCargo.supported(recipe)) return null;
		if (data == null || camp == null || actor == null || inventory == null || worldValid == null
			|| demandStillNeeded == null || !data.canonicalSettlement(camp) || !data.isMember(camp, actor)
			|| data.getStockpile(camp).isEmpty() || station(data, camp, recipe).isEmpty()) return null;
		// Clear existing physical custody first, including malformed/future markers.
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (SettlementWorkbenchCargo.isMarked(inventory.getItem(slot))) return null;
		}
		Ticket ticket = new Ticket(data, camp, actor, inventory, recipe, worldValid, demandStillNeeded);
		return valid(ticket, data, camp, actor, inventory) ? ticket : null;
	}

	static boolean valid(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		if (t == null || t.used || !currentIdentity(t, data, camp, actor, inventory)) return false;
		if (t.collected && !hasExactInputs(t, inventory)) return false;
		if (!t.demand.getAsBoolean()) return false;
		return !strictCallbacks(t) || (!t.used && identityMatches(t, data, camp, actor, inventory)
			&& (!t.collected || hasExactInputs(t, inventory)));
	}

	private static boolean strictCallbacks(Ticket t) {
		return t.recipe == Recipe.SMOOTH_STONE || t.recipe == Recipe.STONE_PICKAXE || t.recipe == Recipe.STICKS;
	}

	private static boolean currentIdentity(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		return identityMatches(t, data, camp, actor, inventory) && t.worldValid.getAsBoolean()
			&& (!strictCallbacks(t) || identityMatches(t, data, camp, actor, inventory));
	}

	private static boolean identityMatches(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		if (t == null || t.data != data || t.camp != camp || !t.actor.equals(actor)
			|| t.inventory != inventory || !data.canonicalSettlement(camp) || !data.isMember(camp, actor)
			|| t.member != data.memberIdentity(actor) || t.storeToken != data.stockpileBindingToken(camp)
			|| t.stationToken != stationToken(data, camp, t.recipe)
			|| !data.getStockpile(camp).filter(t.store::equals).isPresent()
			|| !station(data, camp, t.recipe).filter(t.station::equals).isPresent()
			|| data.simulationOf(camp).orElse(null) != t.state) return false;
		// Material authority changes invalidate even when toggled off and back on. Skills remain runtime optional.
		if (t.state != null && t.state.foodRequestModeVersion() != t.modeVersion) return false;
		return true;
	}

	static boolean pickup(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
		Container stockpile, BooleanSupplier atStore) {
		if (!valid(t, data, camp, actor, inventory) || t.collected || stockpile == null
			|| stockpile == inventory || atStore == null || !atStore.getAsBoolean()) return fail(t);
		List<ItemStack> inputs = SettlementWorkbenchCargo.inputs(camp.id(), t.batch, t.recipe);
		SimpleContainer bag = pickupPreview(t, inventory, inputs);
		if (bag == null) return fail(t);
		// Preflight the complete physical export, including both tool deficits, before exporting anything.
		Map<Item, Integer> deficits = new LinkedHashMap<>();
		SimpleContainer exportPreview = copy(stockpile);
		for (ItemStack input : inputs) {
			int missing = input.getCount() - SettlementMaterialCrafting.countPlain(stockpile, input.getItem());
			if (missing <= 0) continue;
			if (!canExport(t, input.getItem())
				|| SettlementMaterialCrafting.availablePlain(stockpile, t.state, input.getItem()) < input.getCount()
				|| SettlementStockpile.insert(exportPreview, new ItemStack(input.getItem(), missing)) != missing) return fail(t);
			deficits.put(input.getItem(), missing);
		}
		// Begin immediately before physical export/withdrawal; seal at pickup before walking.
		t.attempt = SettlementSimulationService.beginCrafting(data, camp, actor, t.recipe.id(),
			t.recipe.output(), t.recipe.count());
		for (var deficit : deficits.entrySet()) {
			int missing = deficit.getValue();
			ResourceType type = MinecraftStockpileAdapter.resourceType(new ItemStack(deficit.getKey())).orElseThrow();
			for (var batch : t.state.world().batchesAt(t.state.campOwner())) {
				if (missing == 0) break;
				if (batch.resourceType() != type) continue;
				if (!valid(t, data, camp, actor, inventory) || !atStore.getAsBoolean()
					|| !canExport(t, deficit.getKey())) return fail(t);
				if (strictCallbacks(t) && !valid(t, data, camp, actor, inventory)) return fail(t);
				int exported = MinecraftStockpileAdapter.exportToContainer(t.state.world(), t.state.campOwner(),
					batch.batchId(), missing, stockpile, t.state.residentId(actor).map(List::of).orElse(List.of()),
					Map.of("source", "minecraft_material_crafting", "activity", "craft", "recipe", t.recipe.id(),
						"villagerId", actor.toString()));
				if (exported > 0) data.setDirty();
				missing -= exported;
			}
			if (missing != 0) return fail(t); // Previously exported items stay real and public.
		}
		if (!valid(t, data, camp, actor, inventory) || !atStore.getAsBoolean()) return fail(t);
		if (strictCallbacks(t) && !valid(t, data, camp, actor, inventory)) return fail(t);
		// Standard non-reentrant containers only. Preview both complete results before changing either.
		bag = pickupPreview(t, inventory, inputs);
		if (bag == null) return fail(t);
		SimpleContainer store = copy(stockpile);
		for (ItemStack input : inputs) {
			int remaining = input.getCount();
			for (int slot = 0; slot < store.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = store.getItem(slot);
				if (!stack.isEmpty() && stack.is(input.getItem()) && stack.getComponentsPatch().isEmpty())
					remaining -= store.removeItem(slot, Math.min(remaining, stack.getCount())).getCount();
			}
			if (remaining != 0) return fail(t);
		}
		apply(stockpile, store);
		apply(inventory, bag);
		SettlementSimulationService.sealCraftingInputs(t.attempt);
		t.collected = true;
		data.setDirty();
		return true;
	}

	static boolean finish(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
		BooleanSupplier atStation) {
		if (!valid(t, data, camp, actor, inventory) || !t.collected || atStation == null
			|| !atStation.getAsBoolean()) return fail(t);
		// Arrival callbacks may observe changed hand/demand or binding authority.
		if (strictCallbacks(t) && !valid(t, data, camp, actor, inventory)) return fail(t);
		t.used = true;
		SimpleContainer result = craftPreview(t, inventory);
		if (result == null) return false;
		apply(inventory, result);
		t.finished = true;
		SettlementSimulationService.recordCrafting(data, camp, actor, t.attempt);
		data.setDirty();
		return true;
	}

	static void cancel(Ticket ticket) { if (ticket != null) ticket.used = true; }
	private static boolean fail(Ticket ticket) { cancel(ticket); return false; }
	/** Consumes the single immediate equipment attempt, including unsuccessful attempts. */
	static boolean claimFinishedPickaxe(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		if (t == null || t.equipmentAttempted) return false;
		t.equipmentAttempted = true;
		return isFinishedPickaxeCurrent(t, data, camp, actor, inventory);
	}

	static boolean isFinishedPickaxeCurrent(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		return t != null && t.finished && t.used && t.recipe == Recipe.STONE_PICKAXE
			&& currentIdentity(t, data, camp, actor, inventory);
	}

	static boolean matchesFinishedPickaxe(Ticket t, ItemStack stack) {
		return t != null && t.finished && t.recipe == Recipe.STONE_PICKAXE
			&& SettlementWorkbenchCargo.matchesOutput(stack, t.camp.id(), t.batch, Recipe.STONE_PICKAXE);
	}

	private static boolean canExport(Ticket t, Item item) {
		if (t.state == null || !t.state.survivalEnabled() || !t.state.productionEnabled()) return false;
		return (t.recipe == Recipe.SMOOTH_STONE && item == Items.COBBLESTONE)
			|| ((t.recipe == Recipe.PLANKS || t.recipe == Recipe.STICKS) && item == Items.OAK_LOG)
			|| ((t.recipe == Recipe.TORCH_COAL || t.recipe == Recipe.TORCH_CHARCOAL) && item == Items.STICK)
			|| (t.recipe == Recipe.STONE_PICKAXE && (item == Items.COBBLESTONE || item == Items.STICK));
	}

	private static boolean hasExactInputs(Ticket t, Container inventory) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!SettlementWorkbenchCargo.isMarked(stack)) continue;
			if (!SettlementWorkbenchCargo.matchesInput(stack, t.camp.id(), t.batch, t.recipe)) return false;
			int count = counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			if (count > t.recipe.ingredients().getOrDefault(stack.getItem(), 0)) return false;
		}
		return counts.equals(t.recipe.ingredients());
	}

	private static SimpleContainer pickupPreview(Ticket t, Container inventory, List<ItemStack> inputs) {
		SimpleContainer bag = copy(inventory);
		for (ItemStack input : inputs)
			if (SettlementStockpile.insert(bag, input) != input.getCount()) return null;
		return craftPreview(t, bag) == null ? null : bag;
	}

	/** Work only on a copy: insufficient/duplicated custody or output restrictions keep every real input. */
	private static SimpleContainer craftPreview(Ticket t, Container inventory) {
		if (!hasExactInputs(t, inventory)) return null;
		SimpleContainer result = copy(inventory);
		for (int slot = 0; slot < result.getContainerSize(); slot++) {
			ItemStack stack = result.getItem(slot);
			if (SettlementWorkbenchCargo.matchesInput(stack, t.camp.id(), t.batch, t.recipe))
				result.removeItem(slot, stack.getCount());
		}
		ItemStack output = SettlementWorkbenchCargo.output(t.camp.id(), t.batch, t.recipe);
		return SettlementStockpile.insert(result, output) == output.getCount() ? result : null;
	}
	private static SimpleContainer copy(Container source) {
		SimpleContainer result = new SimpleContainer(source.getContainerSize()) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return source.canPlaceItem(slot, stack); }
			@Override public int getMaxStackSize() { return source.getMaxStackSize(); }
			@Override public int getMaxStackSize(ItemStack stack) { return source.getMaxStackSize(stack); }
		};
		for (int slot = 0; slot < source.getContainerSize(); slot++) result.setItem(slot, source.getItem(slot).copy());
		return result;
	}
	private static void apply(Container target, Container result) {
		for (int slot = 0; slot < target.getContainerSize(); slot++) {
			if (!ItemStack.matches(target.getItem(slot), result.getItem(slot))) target.setItem(slot, result.getItem(slot).copy());
		}
		target.setChanged();
	}
}
