package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Norm {
	public static final String NEED_FIRST_COMMON_STORE = "need-first-common-store";

	private final String normId;
	private final String campId;
	private final int allocationUnitCap;
	private final Set<String> supporters;
	private final Set<String> opponents;
	private final Set<String> knownBy;
	private final List<Revision> revisionHistory;
	private Status status;
	private int stability;

	public Norm(
		String normId,
		String campId,
		int allocationUnitCap,
		Set<String> supporters,
		Set<String> opponents,
		Set<String> knownBy,
		int stability,
		Status status,
		List<Revision> revisionHistory
	) {
		if (normId.isBlank() || campId.isBlank() || allocationUnitCap <= 0 || stability < 0 || stability > 100) {
			throw new IllegalArgumentException("Invalid norm definition");
		}
		this.normId = normId;
		this.campId = campId;
		this.allocationUnitCap = allocationUnitCap;
		this.supporters = new LinkedHashSet<>(supporters);
		this.opponents = new LinkedHashSet<>(opponents);
		this.knownBy = new LinkedHashSet<>(knownBy);
		this.stability = stability;
		this.status = status;
		this.revisionHistory = new ArrayList<>(revisionHistory);
	}

	public String normId() {
		return normId;
	}

	public String campId() {
		return campId;
	}

	public int allocationUnitCap() {
		return allocationUnitCap;
	}

	public Set<String> supporters() {
		return Set.copyOf(supporters);
	}

	public Set<String> opponents() {
		return Set.copyOf(opponents);
	}

	public Set<String> knownBy() {
		return Set.copyOf(knownBy);
	}

	public int stability() {
		return stability;
	}

	public Status status() {
		return status;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	public List<Revision> revisionHistory() {
		return List.copyOf(revisionHistory);
	}

	void learn(String personId, boolean supporter) {
		knownBy.add(personId);
		if (supporter) {
			supporters.add(personId);
			opponents.remove(personId);
		} else {
			opponents.add(personId);
			supporters.remove(personId);
		}
	}

	boolean recordPractice(boolean compliant) {
		stability = Math.max(0, Math.min(100, stability + (compliant ? 1 : -8)));
		if (stability == 0) {
			status = Status.DORMANT;
			return true;
		}
		return false;
	}

	void recordRevision(Revision revision) {
		revisionHistory.add(revision);
	}

	public enum Status {
		ACTIVE,
		DORMANT
	}

	public record Revision(long day, String proposalId, long eventId, int previousCap, int newCap, Action action) {
		public enum Action {
			ADOPTED,
			REVISED,
			DORMANT
		}
	}
}
