package com.thysummer.coopcraft.civilization.simulation;

public record CampKnowledge(
	String campId,
	int foodSecurity,
	long reportedDay,
	long learnedDay,
	String learnedFrom,
	double confidence,
	long sourceEventId
) {
	public CampKnowledge {
		if (campId.isBlank() || foodSecurity < 0 || foodSecurity > 100 || reportedDay < 0 || learnedDay < reportedDay
			|| sourceEventId <= 0 || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
			throw new IllegalArgumentException("Invalid camp knowledge");
		}
	}

	public CampKnowledge propagate(long day, String sourcePersonId) {
		if (day < learnedDay || sourcePersonId.isBlank()) {
			throw new IllegalArgumentException("Invalid knowledge propagation");
		}
		return new CampKnowledge(
			campId, foodSecurity, reportedDay, day, sourcePersonId, confidence * 0.85, sourceEventId
		);
	}

	public double effectiveConfidence(long currentDay) {
		long age = Math.max(0, currentDay - reportedDay);
		return confidence * Math.pow(0.99, age);
	}
}
