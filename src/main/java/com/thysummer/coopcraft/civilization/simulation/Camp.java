package com.thysummer.coopcraft.civilization.simulation;

public record Camp(String id, String name) {
	public Camp {
		if (id.isBlank() || name.isBlank()) {
			throw new IllegalArgumentException("Camp ID and name cannot be blank");
		}
	}
}
