package com.thysummer.coopcraft.civilization.simulation;

public record Household(String id, String campId) {
	public Household {
		if (id.isBlank() || campId.isBlank()) {
			throw new IllegalArgumentException("Household and camp IDs cannot be blank");
		}
	}
}
