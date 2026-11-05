package com.thysummer.coopcraft.civilization;

import java.util.HashSet;
import java.util.List;

public record ResidenceLink(String householdId, List<String> memberIds) {
	public ResidenceLink {
		if (householdId.isBlank() || memberIds.isEmpty()) {
			throw new IllegalArgumentException("A residence needs a household and at least one member");
		}
		memberIds = List.copyOf(memberIds);
		if (memberIds.stream().anyMatch(String::isBlank) || new HashSet<>(memberIds).size() != memberIds.size()) {
			throw new IllegalArgumentException("Residence members must be nonblank and distinct");
		}
	}
}
