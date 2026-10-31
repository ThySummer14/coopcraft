package com.thysummer.coopcraft.civilization.simulation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class NormProposal {
	private final String proposalId;
	private final String campId;
	private final String normId;
	private final String proposerId;
	private final long proposedDay;
	private final long originEventId;
	private final int allocationUnitCap;
	private final int revision;
	private final Set<String> knownBy = new LinkedHashSet<>();
	private final Map<String, Position> positions = new LinkedHashMap<>();
	private Status status = Status.OPEN;
	private int rounds;

	public NormProposal(
		String proposalId,
		String campId,
		String normId,
		String proposerId,
		long proposedDay,
		long originEventId,
		int allocationUnitCap,
		int revision
	) {
		if (proposalId.isBlank() || campId.isBlank() || normId.isBlank() || proposerId.isBlank()
			|| proposedDay < 0 || originEventId <= 0 || allocationUnitCap <= 0 || revision < 0) {
			throw new IllegalArgumentException("Invalid norm proposal");
		}
		this.proposalId = proposalId;
		this.campId = campId;
		this.normId = normId;
		this.proposerId = proposerId;
		this.proposedDay = proposedDay;
		this.originEventId = originEventId;
		this.allocationUnitCap = allocationUnitCap;
		this.revision = revision;
	}

	public String proposalId() {
		return proposalId;
	}

	public String campId() {
		return campId;
	}

	public String normId() {
		return normId;
	}

	public String proposerId() {
		return proposerId;
	}

	public long proposedDay() {
		return proposedDay;
	}

	public long originEventId() {
		return originEventId;
	}

	public int allocationUnitCap() {
		return allocationUnitCap;
	}

	public int revision() {
		return revision;
	}

	public Status status() {
		return status;
	}

	public int rounds() {
		return rounds;
	}

	public Set<String> knownBy() {
		return Set.copyOf(knownBy);
	}

	public Map<String, Position> positions() {
		return Map.copyOf(positions);
	}

	void learn(String personId, Position position) {
		knownBy.add(personId);
		positions.put(personId, position);
	}

	void updatePosition(String personId, Position position) {
		if (knownBy.contains(personId)) {
			positions.put(personId, position);
		}
	}

	void advanceRound() {
		rounds++;
	}

	void conclude(Status status) {
		if (this.status != Status.OPEN || status == Status.OPEN) {
			throw new IllegalArgumentException("Invalid proposal conclusion");
		}
		this.status = status;
	}

	public enum Status {
		OPEN,
		ADOPTED,
		REJECTED,
		EXPIRED
	}

	public enum Position {
		SUPPORT,
		OPPOSE,
		UNDECIDED
	}
}
