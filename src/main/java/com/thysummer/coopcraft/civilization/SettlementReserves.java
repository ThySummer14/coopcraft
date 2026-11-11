package com.thysummer.coopcraft.civilization;

import java.util.OptionalLong;
import net.minecraft.world.Container;

/** Read-only snapshot of the active food authority. Unknown physical storage stays unknown. */
public final class SettlementReserves {
	private SettlementReserves() {}

	public static boolean setTarget(SettlementSavedData data, Settlement settlement, FoodReserveTarget target) {
		return data.setFoodReserveTarget(settlement, target);
	}

	public static Snapshot inspect(SettlementSavedData data, Settlement settlement, Container stockpile) {
		FoodReserveTarget target = data.foodReserveTargetOf(settlement);
		int residents = data.registeredCount(settlement);
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		boolean survival = state != null && state.survivalEnabled();
		long goal = (long) residents * target.pointsPerMember() * (survival ? 5 : 1);
		OptionalLong current = survival ? OptionalLong.of(state.world().availableNutrition(state.campOwner()))
			: stockpile == null ? OptionalLong.empty() : OptionalLong.of(SettlementStockpile.communalFoodPoints(stockpile));
		return new Snapshot(target, residents, survival, current, goal);
	}

	public record Snapshot(FoodReserveTarget target, int residents, boolean survival, OptionalLong current, long goal) {
		public OptionalLong shortfall() {
			return current.isPresent() ? OptionalLong.of(Math.max(0, goal - current.getAsLong())) : OptionalLong.empty();
		}
	}
}
