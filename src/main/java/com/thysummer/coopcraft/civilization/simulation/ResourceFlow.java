package com.thysummer.coopcraft.civilization.simulation;

import java.util.Objects;

public record ResourceFlow(
	String batchId,
	ResourceType resourceType,
	long quantity,
	long nutritionPerUnit,
	String fromLocation,
	String toLocation
) {
	public ResourceFlow {
		Objects.requireNonNull(resourceType, "resourceType");
		if (quantity <= 0 || nutritionPerUnit < 0) {
			throw new IllegalArgumentException("Flow quantity must be positive and nutrition nonnegative");
		}
		if (fromLocation == null && toLocation == null) {
			throw new IllegalArgumentException("A flow needs a source or destination");
		}
	}

	long nutrition() {
		return Math.multiplyExact(quantity, nutritionPerUnit);
	}
}
