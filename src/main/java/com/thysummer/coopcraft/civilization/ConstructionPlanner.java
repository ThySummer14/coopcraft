package com.thysummer.coopcraft.civilization;

public final class ConstructionPlanner {
	private ConstructionPlanner() {
	}

	public static HousingPlan housingPlan(SettlementSavedData data, Settlement settlement) {
		return new HousingPlan(data.registeredCount(settlement), data.housingCapacity(settlement));
	}

	public record HousingPlan(int registeredMembers, int housingCapacity) {
		public HousingPlan {
			if (registeredMembers < 0 || housingCapacity < 0) {
				throw new IllegalArgumentException("Housing counts cannot be negative");
			}
		}

		public int missingBeds() {
			return Math.max(0, registeredMembers - housingCapacity);
		}

		public boolean needsHousing() {
			return missingBeds() > 0;
		}
	}
}
