package com.thysummer.coopcraft.civilization.simulation;

public enum WorkType {
	GATHER,
	FISH,
	HUNT,
	CRAFT;

	private static final WorkType[] HARVESTING_TYPES = {GATHER, FISH, HUNT};

	public static WorkType[] harvestingTypes() {
		return HARVESTING_TYPES.clone();
	}
}
