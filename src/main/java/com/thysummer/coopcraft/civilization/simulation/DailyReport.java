package com.thysummer.coopcraft.civilization.simulation;

public record DailyReport(
	long day,
	long producedNutrition,
	long consumedNutrition,
	long spoiledNutrition,
	long laborHours,
	int hungryPeople,
	int foodAccessCount,
	int shareAcceptances,
	int shareRefusals
) {
	public double foodAccessRate(int population) {
		if (population <= 0) {
			return 0;
		}
		return (double) foodAccessCount / population;
	}

	public double effectiveLaborYield() {
		return laborHours == 0 ? 0 : (double) producedNutrition / laborHours;
	}
}
