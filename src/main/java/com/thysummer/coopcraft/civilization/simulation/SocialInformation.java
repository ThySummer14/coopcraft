package com.thysummer.coopcraft.civilization.simulation;

import java.util.Objects;

public record SocialInformation(
	String informationId,
	Kind kind,
	String subjectId,
	String objectId,
	long sourceEventId,
	long learnedDay,
	String learnedFrom,
	double confidence
) {
	public SocialInformation {
		if (informationId.isBlank() || subjectId.isBlank() || objectId.isBlank()) {
			throw new IllegalArgumentException("Information, subject and object IDs cannot be blank");
		}
		Objects.requireNonNull(kind, "kind");
		if (sourceEventId <= 0 || learnedDay < 0 || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
			throw new IllegalArgumentException("Invalid information provenance or confidence");
		}
	}

	public SocialInformation propagate(long day, String sourcePersonId) {
		if (sourcePersonId.isBlank()) {
			throw new IllegalArgumentException("Information source cannot be blank");
		}
		return new SocialInformation(
			informationId, kind, subjectId, objectId, sourceEventId, day, sourcePersonId,
			Math.max(0, confidence * 0.85)
		);
	}

	public enum Kind {
		SHARE_REFUSAL,
		SHORTAGE,
		NORM_PROPOSAL,
		NORM_ADOPTION,
		NORM_REVISION,
		NORM_VIOLATION
	}
}
