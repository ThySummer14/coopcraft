package com.thysummer.coopcraft.civilization.simulation;

public record SimulationConfig(
	int dailyHunger,
	int effortHours,
	long carryCapacity,
	double baseYieldPerHour,
	int fieldEatingThreshold,
	int publicContributionPercent,
	int foodAccessThreshold,
	double shareAcceptanceThreshold,
	boolean sharingEnabled,
	boolean publicStorageEnabled,
	double seasonMultiplier
) {
	public SimulationConfig {
		if (dailyHunger <= 0 || effortHours <= 0 || effortHours > 12 || carryCapacity <= 0
			|| fieldEatingThreshold < 0 || fieldEatingThreshold > 100 || foodAccessThreshold < 0
			|| foodAccessThreshold > 100 || publicContributionPercent < 0 || publicContributionPercent > 100) {
			throw new IllegalArgumentException("Invalid simulation limits");
		}
		if (!Double.isFinite(baseYieldPerHour) || baseYieldPerHour <= 0
			|| !Double.isFinite(shareAcceptanceThreshold) || !Double.isFinite(seasonMultiplier) || seasonMultiplier < 0) {
			throw new IllegalArgumentException("Simulation coefficients must be finite");
		}
	}

	public static SimulationConfig defaults() {
		return new SimulationConfig(35, 6, 8, 0.5, 75, 20, 10, 40, true, true, 1.0);
	}

	public SimulationConfig withSharing(boolean enabled) {
		return new SimulationConfig(
			dailyHunger, effortHours, carryCapacity, baseYieldPerHour, fieldEatingThreshold,
			publicContributionPercent, foodAccessThreshold, shareAcceptanceThreshold, enabled,
			publicStorageEnabled, seasonMultiplier
		);
	}

	public SimulationConfig withPublicStorage(boolean enabled) {
		return new SimulationConfig(
			dailyHunger, effortHours, carryCapacity, baseYieldPerHour, fieldEatingThreshold,
			publicContributionPercent, foodAccessThreshold, shareAcceptanceThreshold, sharingEnabled, enabled,
			seasonMultiplier
		);
	}
}
