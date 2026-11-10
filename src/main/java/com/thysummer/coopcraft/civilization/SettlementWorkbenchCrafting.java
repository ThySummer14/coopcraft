package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-thread-only, single-use plank batches. Tickets never own items or survive reload. */
final class SettlementWorkbenchCrafting {
	private SettlementWorkbenchCrafting() { }

	static final class Ticket {
		private final SettlementSavedData data;
		private final Settlement camp;
		private final UUID actor;
		private final Container inventory;
		private final Object member, storeToken, stationToken;
		private final BlockPos store, station;
		private final SettlementSimulationState state;
		private final long modeVersion;
		private final BooleanSupplier worldValid, demand;
		private final UUID batch = UUID.randomUUID();
		private boolean collected, used;
		private SettlementSimulationService.CraftingAttempt attempt;

		private Ticket(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
			BooleanSupplier worldValid, BooleanSupplier demand) {
			this.data = data;
			this.camp = camp;
			this.actor = actor;
			this.inventory = inventory;
			this.worldValid = worldValid;
			this.demand = demand;
			member = data.memberIdentity(actor);
			storeToken = data.stockpileBindingToken(camp);
			stationToken = data.craftingStationBindingToken(camp);
			store = data.getStockpile(camp).orElseThrow();
			station = data.getCraftingStation(camp).orElseThrow();
			state = data.simulationOf(camp).orElse(null);
			modeVersion = state == null ? 0 : state.foodRequestModeVersion();
		}
		BlockPos store() { return store; }
		BlockPos station() { return station; }
		boolean collected() { return collected; }
	}

	/** Read-only: neither reserves supplies nor starts a provenance attempt. */
	static Ticket prepare(SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
		BooleanSupplier worldValid, BooleanSupplier demandStillNeeded) {
		if (data == null || camp == null || actor == null || inventory == null || worldValid == null
			|| demandStillNeeded == null || !data.canonicalSettlement(camp) || !data.isMember(camp, actor)
			|| data.getStockpile(camp).isEmpty() || data.getCraftingStation(camp).isEmpty()) return null;
		// Clear existing physical custody first, including malformed/future markers.
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (SettlementWorkbenchCargo.isMarked(inventory.getItem(slot))) return null;
		}
		Ticket ticket = new Ticket(data, camp, actor, inventory, worldValid, demandStillNeeded);
		return valid(ticket, data, camp, actor, inventory) ? ticket : null;
	}

	static boolean valid(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory) {
		if (t == null || t.used || t.data != data || t.camp != camp || !t.actor.equals(actor)
			|| t.inventory != inventory || !data.canonicalSettlement(camp) || !data.isMember(camp, actor)
			|| t.member != data.memberIdentity(actor) || t.storeToken != data.stockpileBindingToken(camp)
			|| t.stationToken != data.craftingStationBindingToken(camp)
			|| !data.getStockpile(camp).filter(t.store::equals).isPresent()
			|| !data.getCraftingStation(camp).filter(t.station::equals).isPresent()
			|| data.simulationOf(camp).orElse(null) != t.state) return false;
		// Material authority changes invalidate even when toggled off and back on. Skills remain runtime optional.
		if (t.state != null && t.state.foodRequestModeVersion() != t.modeVersion) return false;
		if (t.collected) {
			int inputs = 0;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				if (SettlementWorkbenchCargo.matchesInput(inventory.getItem(slot), camp.id(), t.batch)) inputs++;
			}
			if (inputs != 1) return false;
		}
		return t.worldValid.getAsBoolean() && t.demand.getAsBoolean();
	}

	static boolean pickup(Ticket t, SettlementSavedData data, Settlement camp, UUID actor, Container inventory,
		Container stockpile, BooleanSupplier atStore) {
		if (!valid(t, data, camp, actor, inventory) || t.collected || stockpile == null
			|| stockpile == inventory || atStore == null || !atStore.getAsBoolean()) return fail(t);
		ItemStack input = SettlementWorkbenchCargo.input(camp.id(), t.batch);
		ItemStack output = SettlementWorkbenchCargo.output(camp.id(), t.batch);
		SimpleContainer bag = copy(inventory);
		if (SettlementStockpile.insert(bag, input) != 1
			|| !SettlementPersonalCrafting.canCraft(bag,
				stack -> SettlementWorkbenchCargo.matchesInput(stack, camp.id(), t.batch), ItemStack.EMPTY, output)) return fail(t);
		int slot = plainLog(stockpile);
		if (slot < 0 && (!canExport(t) || SettlementMaterialCrafting.availablePlain(stockpile, t.state, Items.OAK_LOG) < 1)) return fail(t);
		// Begin immediately before the physical withdrawal/export, never while waiting or walking.
		t.attempt = SettlementSimulationService.beginCrafting(data, camp, actor, "material_planks", Items.OAK_PLANKS, 4);
		if (slot < 0) {
			for (var batch : t.state.world().batchesAt(t.state.campOwner())) {
				if (batch.resourceType() != ResourceType.WOOD) continue;
				int exported = MinecraftStockpileAdapter.exportToContainer(t.state.world(), t.state.campOwner(),
					batch.batchId(), 1, stockpile, t.state.residentId(actor).map(List::of).orElse(List.of()),
					Map.of("source", "minecraft_material_crafting", "activity", "craft", "recipe", "material_planks",
						"villagerId", actor.toString()));
				if (exported > 0) { data.setDirty(); break; }
			}
			slot = plainLog(stockpile);
		}
		if (slot < 0 || !valid(t, data, camp, actor, inventory) || !atStore.getAsBoolean()) return fail(t);
		// Standard non-reentrant containers only. Both complete results are previewed before either is changed.
		bag = copy(inventory);
		if (SettlementStockpile.insert(bag, input) != 1
			|| !SettlementPersonalCrafting.canCraft(bag,
				stack -> SettlementWorkbenchCargo.matchesInput(stack, camp.id(), t.batch), ItemStack.EMPTY, output)) return fail(t);
		SimpleContainer store = copy(stockpile);
		if (store.removeItem(slot, 1).getCount() != 1) return fail(t);
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
		t.used = true;
		if (!SettlementPersonalCrafting.craft(inventory,
			stack -> SettlementWorkbenchCargo.matchesInput(stack, camp.id(), t.batch),
			SettlementWorkbenchCargo.output(camp.id(), t.batch))) return false;
		SettlementSimulationService.recordCrafting(data, camp, actor, t.attempt);
		data.setDirty();
		return true;
	}

	static void cancel(Ticket ticket) { if (ticket != null) ticket.used = true; }
	private static boolean fail(Ticket ticket) { cancel(ticket); return false; }
	private static boolean canExport(Ticket t) {
		return t.state != null && t.state.survivalEnabled() && t.state.productionEnabled();
	}
	private static int plainLog(Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty() && stack.is(Items.OAK_LOG) && stack.getComponentsPatch().isEmpty()) return slot;
		}
		return -1;
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
