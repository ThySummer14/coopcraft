package com.thysummer.coopcraft.civilization;

public enum VillagerTrait {
	GREEDY("greedy", "greed", 0, 8, 100, 100, 100, 100, 100, 0),
	GENEROUS("generous", "greed", 0, -12, 100, 100, 100, 100, 100, 0),
	GLUTTONOUS("gluttonous", "gluttonous", 4, 0, 100, 100, 100, 100, 100, 0),
	FAST("fast", "speed", 0, 0, 100, 100, 100, 100, 120, 0),
	SLOW("slow", "speed", 0, 0, 100, 100, 100, 100, 85, 0),
	STRONG("strong", "strength", 0, 0, 85, 100, 100, 100, 100, 0),
	WEAK("weak", "strength", 0, 0, 115, 100, 100, 100, 100, 0),
	WISE("wise", "wise", 0, 0, 100, 90, 100, 100, 100, 0),
	FAST_BUILDER("fast_builder", "build_speed", 0, 0, 100, 100, 85, 100, 100, 0),
	SLOW_BUILDER("slow_builder", "build_speed", 0, 0, 100, 100, 115, 100, 100, 0),
	ATTRACTIVE("attractive", "appearance", 0, 0, 100, 100, 100, 100, 100, 20),
	UGLY("ugly", "appearance", 0, 0, 100, 100, 100, 100, 100, -20),
	LUCKY("lucky", "luck", 0, 0, 100, 100, 100, 95, 100, 0),
	UNLUCKY("unlucky", "luck", 0, 0, 100, 100, 100, 105, 100, 0);

	private final String id;
	private final String conflictGroup;
	private final int basicFoodBonus;
	private final int surplusFoodBonus;
	private final int gatherDurationPercent;
	private final int craftDurationPercent;
	private final int buildDurationPercent;
	private final int generalWorkDurationPercent;
	private final int movementSpeedPercent;
	private final int leadershipBonus;

	VillagerTrait(
		String id,
		String conflictGroup,
		int basicFoodBonus,
		int surplusFoodBonus,
		int gatherDurationPercent,
		int craftDurationPercent,
		int buildDurationPercent,
		int generalWorkDurationPercent,
		int movementSpeedPercent,
		int leadershipBonus
	) {
		this.id = id;
		this.conflictGroup = conflictGroup;
		this.basicFoodBonus = basicFoodBonus;
		this.surplusFoodBonus = surplusFoodBonus;
		this.gatherDurationPercent = gatherDurationPercent;
		this.craftDurationPercent = craftDurationPercent;
		this.buildDurationPercent = buildDurationPercent;
		this.generalWorkDurationPercent = generalWorkDurationPercent;
		this.movementSpeedPercent = movementSpeedPercent;
		this.leadershipBonus = leadershipBonus;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "coopcraft.trait." + id;
	}

	public String conflictGroup() {
		return conflictGroup;
	}

	public int basicFoodBonus() {
		return basicFoodBonus;
	}

	public int surplusFoodBonus() {
		return surplusFoodBonus;
	}

	public int gatherDurationPercent() {
		return gatherDurationPercent;
	}

	public int craftDurationPercent() {
		return craftDurationPercent;
	}

	public int buildDurationPercent() {
		return buildDurationPercent;
	}

	public int generalWorkDurationPercent() {
		return generalWorkDurationPercent;
	}

	public int movementSpeedPercent() {
		return movementSpeedPercent;
	}

	public int leadershipBonus() {
		return leadershipBonus;
	}

	public static VillagerTrait byId(String id) {
		for (VillagerTrait value : values()) {
			if (value.id.equals(id)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown villager trait: " + id);
	}
}
