package com.thysummer.coopcraft.civilization;

public enum SettlementStage {
	PRIMITIVE("primitive"),
	AGRICULTURAL("agricultural"),
	TOWN("town");

	private final String id;

	SettlementStage(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "coopcraft.stage." + id;
	}

	public static SettlementStage byId(String id) {
		for (SettlementStage stage : values()) {
			if (stage.id.equals(id)) {
				return stage;
			}
		}
		throw new IllegalArgumentException("Unknown settlement stage: " + id);
	}
}
