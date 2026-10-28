package com.thysummer.coopcraft.civilization.simulation;

public record DemographyConfig(
	boolean enabled,
	int daysPerYear,
	int infantEndYears,
	int childEndYears,
	int adolescentEndYears,
	int elderStartYears,
	int infantCareHours,
	int childCareHours,
	int adolescentCareHours,
	int maxCareHoursPerPerson,
	int adolescentWorkHours,
	int maxPopulation,
	int minBirthFoodSecurity,
	double birthChancePerDay,
	double elderDailyMortality
) {
	public DemographyConfig {
		if (daysPerYear <= 0 || infantEndYears <= 0 || childEndYears <= infantEndYears
			|| adolescentEndYears <= childEndYears || elderStartYears <= adolescentEndYears
			|| infantCareHours <= 0 || childCareHours <= 0 || adolescentCareHours < 0
			|| maxCareHoursPerPerson <= 0 || maxCareHoursPerPerson > 6 || adolescentWorkHours <= 0
			|| maxPopulation <= 0 || minBirthFoodSecurity < 0 || minBirthFoodSecurity > 100) {
			throw new IllegalArgumentException("Invalid demography limits");
		}
		if (!Double.isFinite(birthChancePerDay) || birthChancePerDay < 0 || birthChancePerDay > 1
			|| !Double.isFinite(elderDailyMortality) || elderDailyMortality < 0 || elderDailyMortality > 1) {
			throw new IllegalArgumentException("Demography probabilities must be within 0..1");
		}
	}

	public static DemographyConfig disabled() {
		return new DemographyConfig(false, 360, 3, 12, 18, 60, 4, 2, 1, 4, 3, 40, 75, 0.001, 0.0005);
	}

	public static DemographyConfig enabledDefaults() {
		return new DemographyConfig(true, 360, 3, 12, 18, 60, 4, 2, 1, 4, 3, 40, 75, 0.001, 0.0005);
	}
}
