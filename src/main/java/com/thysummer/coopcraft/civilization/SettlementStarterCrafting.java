package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;

/** A single transient hand-crafting batch for the first registered workshop only. Server thread only. */
final class SettlementStarterCrafting {
	private SettlementStarterCrafting() { }

	static boolean eligible(SettlementSavedData data, Settlement settlement) {
		if (data == null || settlement == null) return false;
		return data.getStockpile(settlement).isPresent()
			&& data.getCraftingStation(settlement).isEmpty()
			&& data.getConstructionJob(settlement).isEmpty()
			&& ConstructionPlanner.housingPlan(data, settlement).needsHousing()
			&& ConstructionWorkshop.supported(BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP));
	}

	static Ticket prepare(SettlementSavedData data, Settlement settlement, UUID actor,
		Container stockpile, BooleanSupplier storeValid) {
		if (stockpile == null || storeValid == null || !eligible(data, settlement)
			|| !data.isMember(settlement, actor) || !storeValid.getAsBoolean()) return null;
		var recipe = choose(data, settlement, stockpile);
		return recipe == null ? null : new Ticket(data, settlement, actor, storeValid, recipe);
	}

	/** No inventory reads/copies: the final transaction reselects against current real stock. */
	static boolean valid(Ticket ticket, SettlementSavedData data, Settlement settlement, UUID actor) {
		return ticket != null && !ticket.used && ticket.data == data && settlement != null
			&& ticket.camp.equals(settlement.id()) && ticket.actor.equals(actor)
			&& eligible(data, settlement) && data.isMember(settlement, actor)
			&& ticket.member == data.memberIdentity(actor)
			&& ticket.binding == data.stockpileBindingToken(settlement)
			&& data.getStockpile(settlement).filter(ticket.store::equals).isPresent()
			&& ticket.fingerprint.equals(BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP).fingerprint())
			&& ticket.storeValid.getAsBoolean();
	}

	static boolean finish(Ticket ticket, SettlementSavedData data, Settlement settlement, UUID actor,
		Container stockpile) {
		if (ticket == null || ticket.used) return false;
		boolean valid = valid(ticket, data, settlement, actor);
		// Even a failed final attempt cannot later be reused after conditions change back.
		ticket.used = true;
		if (!valid || stockpile == null || choose(data, settlement, stockpile) != ticket.recipe) return false;
		var recipe = ticket.recipe;
		var attempt = SettlementSimulationService.beginCrafting(data, settlement, actor,
			recipe.id(), recipe.output(), recipe.count());
		if (!SettlementMaterialCrafting.craft(stockpile, data.simulationOf(settlement).orElse(null),
			recipe, actor, data::setDirty)) return false;
		SettlementSimulationService.recordCrafting(data, settlement, actor, attempt);
		return true;
	}

	static boolean needsLog(SettlementSavedData data, Settlement settlement, Container stockpile) {
		if (stockpile == null || !eligible(data, settlement)) return false;
		var state = data.simulationOf(settlement).orElse(null);
		return ConstructionMaterials.available(stockpile, state, Items.CRAFTING_TABLE) < 1
			&& SettlementMaterialCrafting.countPlain(stockpile, Items.OAK_PLANKS) < 4
			&& SettlementMaterialCrafting.availablePlain(stockpile, state, Items.OAK_LOG) < 1;
	}

	private static SettlementMaterialCrafting.Recipe choose(SettlementSavedData data, Settlement settlement,
		Container stockpile) {
		var state = data.simulationOf(settlement).orElse(null);
		if (ConstructionMaterials.available(stockpile, state, Items.CRAFTING_TABLE) >= 1) return null;
		return SettlementMaterialCrafting.chooseRecipe(stockpile, state, Map.of(Items.CRAFTING_TABLE, 1))
			.filter(recipe -> recipe == SettlementMaterialCrafting.Recipe.PLANKS
				|| recipe == SettlementMaterialCrafting.Recipe.CRAFTING_TABLE).orElse(null);
	}

	static final class Ticket {
		private final SettlementSavedData data;
		private final UUID camp;
		private final UUID actor;
		private final Object member;
		private final Object binding;
		private final BlockPos store;
		private final String fingerprint;
		private final BooleanSupplier storeValid;
		private final SettlementMaterialCrafting.Recipe recipe;
		private boolean used;

		private Ticket(SettlementSavedData data, Settlement settlement, UUID actor,
			BooleanSupplier storeValid, SettlementMaterialCrafting.Recipe recipe) {
			this.data = data;
			this.camp = settlement.id();
			this.actor = actor;
			this.member = data.memberIdentity(actor);
			this.binding = data.stockpileBindingToken(settlement);
			this.store = data.getStockpile(settlement).orElseThrow();
			this.fingerprint = BlueprintManager.get(ConstructionPlanner.STARTER_WORKSHOP).fingerprint();
			this.storeValid = storeValid;
			this.recipe = recipe;
		}

		SettlementMaterialCrafting.Recipe recipe() { return recipe; }
		BlockPos store() { return store; }
	}
}
