package com.thysummer.coopcraft.civilization.simulation;

public enum ResourceType {
	WILD_FRUIT(WorkType.GATHER, 18, 3),
	TUBERS(WorkType.GATHER, 24, 8),
	FISH(WorkType.FISH, 20, 2),
	GAME(WorkType.HUNT, 28, 2),
	WOOD(WorkType.GATHER, 0, Long.MAX_VALUE),
	STONE(WorkType.GATHER, 0, Long.MAX_VALUE);

	private final WorkType workType;
	private final int nutritionPerUnit;
	private final long shelfLifeDays;

	ResourceType(WorkType workType, int nutritionPerUnit, long shelfLifeDays) {
		this.workType = workType;
		this.nutritionPerUnit = nutritionPerUnit;
		this.shelfLifeDays = shelfLifeDays;
	}

	public WorkType workType() {
		return workType;
	}

	public int nutritionPerUnit() {
		return nutritionPerUnit;
	}

	public long shelfLifeDays() {
		return shelfLifeDays;
	}

	public boolean isFood() {
		return nutritionPerUnit > 0;
	}
}
