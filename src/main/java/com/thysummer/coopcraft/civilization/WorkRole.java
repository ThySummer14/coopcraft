package com.thysummer.coopcraft.civilization;

public enum WorkRole {
	DEPENDENT("dependent"),
	GENERALIST("generalist"),
	FORAGER("forager"),
	MINER("miner"),
	WOODCUTTER("woodcutter"),
	BUILDER("builder"),
	CRAFTER("crafter");

	private final String id;

	WorkRole(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "coopcraft.work_role." + id;
	}

	public static WorkRole byId(String id) {
		for (WorkRole value : values()) {
			if (value.id.equals(id)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown work role: " + id);
	}
}
