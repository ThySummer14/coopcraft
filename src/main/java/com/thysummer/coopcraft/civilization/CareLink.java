package com.thysummer.coopcraft.civilization;

public record CareLink(long eventId, long day, String caregiverId, String dependentId, int hours) {
	public CareLink {
		if (eventId <= 0 || day < 0 || hours <= 0 || caregiverId.isBlank() || dependentId.isBlank()
			|| caregiverId.equals(dependentId)) {
			throw new IllegalArgumentException("Invalid care link");
		}
	}
}
