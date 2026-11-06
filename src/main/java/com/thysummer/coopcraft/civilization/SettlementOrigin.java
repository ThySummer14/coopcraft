package com.thysummer.coopcraft.civilization;

public enum SettlementOrigin {
	NEW_CAMP("new_camp"),
	ESTABLISHED_VILLAGE("established_village");

	private final String id;

	SettlementOrigin(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "coopcraft.origin." + id;
	}

	public static SettlementOrigin byId(String id) {
		for (SettlementOrigin origin : values()) {
			if (origin.id.equals(id)) {
				return origin;
			}
		}
		throw new IllegalArgumentException("Unknown settlement origin: " + id);
	}
}
