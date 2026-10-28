package com.thysummer.coopcraft.civilization;

public enum PersonalityArchetype {
	HONEST("honest", -12, 5, 100),
	DECEITFUL("deceitful", 8, 10, 100),
	AMBITIOUS("ambitious", 4, 40, 95),
	CONTENT("content", -4, -40, 100),
	PACIFIST("pacifist", 0, 0, 100),
	BALANCED("balanced", 0, 0, 100);

	private final String id;
	private final int surplusFoodBonus;
	private final int leadershipBonus;
	private final int workDurationPercent;

	PersonalityArchetype(String id, int surplusFoodBonus, int leadershipBonus, int workDurationPercent) {
		this.id = id;
		this.surplusFoodBonus = surplusFoodBonus;
		this.leadershipBonus = leadershipBonus;
		this.workDurationPercent = workDurationPercent;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "coopcraft.personality." + id;
	}

	public int surplusFoodBonus() {
		return surplusFoodBonus;
	}

	public int leadershipBonus() {
		return leadershipBonus;
	}

	public int workDurationPercent() {
		return workDurationPercent;
	}

	public static PersonalityArchetype byId(String id) {
		for (PersonalityArchetype value : values()) {
			if (value.id.equals(id)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown personality archetype: " + id);
	}
}
