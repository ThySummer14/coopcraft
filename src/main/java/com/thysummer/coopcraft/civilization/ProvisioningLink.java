package com.thysummer.coopcraft.civilization;

import java.util.Objects;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;

public record ProvisioningLink(
	long eventId,
	long day,
	String sourceCampId,
	String recipientId,
	ResourceType resourceType,
	long quantity
) {
	public ProvisioningLink {
		Objects.requireNonNull(resourceType, "resourceType");
		if (eventId <= 0 || day < 0 || sourceCampId.isBlank() || recipientId.isBlank() || quantity <= 0
			|| !resourceType.isFood()) {
			throw new IllegalArgumentException("Invalid provisioning link");
		}
	}
}
