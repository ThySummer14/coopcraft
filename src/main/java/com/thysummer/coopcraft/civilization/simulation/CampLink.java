package com.thysummer.coopcraft.civilization.simulation;

import java.util.Optional;

public record CampLink(String routeId, String firstCampId, String secondCampId, int travelHours) {
	public CampLink {
		if (routeId.isBlank() || firstCampId.isBlank() || secondCampId.isBlank() || firstCampId.equals(secondCampId)) {
			throw new IllegalArgumentException("A camp link needs two distinct camps and a route ID");
		}
		if (travelHours <= 0 || travelHours > 6) {
			throw new IllegalArgumentException("Travel hours must be within 1..6");
		}
	}

	public boolean connects(String first, String second) {
		return firstCampId.equals(first) && secondCampId.equals(second)
			|| firstCampId.equals(second) && secondCampId.equals(first);
	}

	public Optional<String> otherCamp(String campId) {
		if (firstCampId.equals(campId)) {
			return Optional.of(secondCampId);
		}
		if (secondCampId.equals(campId)) {
			return Optional.of(firstCampId);
		}
		return Optional.empty();
	}
}
