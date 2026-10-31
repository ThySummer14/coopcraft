package com.thysummer.coopcraft.civilization.simulation;

import java.util.Objects;
import java.util.Set;

public final class ResourceBatch {
	private final String batchId;
	private final ResourceType resourceType;
	private final long nutritionPerUnit;
	private final int quality;
	private final String sourcePatchId;
	private final long originEventId;
	private final Set<String> parentBatchIds;
	private final long harvestDay;
	private final long expiryDay;
	private long quantity;
	private StorageOwner holder;
	private String location;
	private long lastEventId;

	ResourceBatch(
		String batchId,
		ResourceType resourceType,
		long quantity,
		long nutritionPerUnit,
		int quality,
		StorageOwner holder,
		String location,
		String sourcePatchId,
		long originEventId,
		Set<String> parentBatchIds,
		long harvestDay,
		long expiryDay,
		long lastEventId
	) {
		if (batchId.isBlank() || location.isBlank()) {
			throw new IllegalArgumentException("Batch ID and location cannot be blank");
		}
		if (quantity <= 0 || nutritionPerUnit < 0 || quality < 0 || quality > 100 || harvestDay < 0 || expiryDay < harvestDay) {
			throw new IllegalArgumentException("Invalid resource batch values");
		}
		this.batchId = batchId;
		this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
		this.quantity = quantity;
		this.nutritionPerUnit = nutritionPerUnit;
		this.quality = quality;
		this.holder = Objects.requireNonNull(holder, "holder");
		this.location = location;
		this.sourcePatchId = sourcePatchId;
		this.originEventId = originEventId;
		this.parentBatchIds = Set.copyOf(parentBatchIds);
		this.harvestDay = harvestDay;
		this.expiryDay = expiryDay;
		this.lastEventId = lastEventId;
	}

	public String batchId() {
		return batchId;
	}

	public ResourceType resourceType() {
		return resourceType;
	}

	public long quantity() {
		return quantity;
	}

	public long nutritionPerUnit() {
		return nutritionPerUnit;
	}

	public long nutrition() {
		return Math.multiplyExact(quantity, nutritionPerUnit);
	}

	public int quality() {
		return quality;
	}

	public StorageOwner holder() {
		return holder;
	}

	public String location() {
		return location;
	}

	public String sourcePatchId() {
		return sourcePatchId;
	}

	public long originEventId() {
		return originEventId;
	}

	public Set<String> parentBatchIds() {
		return parentBatchIds;
	}

	public long harvestDay() {
		return harvestDay;
	}

	public long expiryDay() {
		return expiryDay;
	}

	public long lastEventId() {
		return lastEventId;
	}

	void remove(long amount) {
		if (amount <= 0 || amount > quantity) {
			throw new IllegalArgumentException("Batch removal exceeds available quantity");
		}
		quantity -= amount;
	}

	void moveTo(StorageOwner newHolder, String newLocation, long eventId) {
		holder = Objects.requireNonNull(newHolder, "newHolder");
		location = Objects.requireNonNull(newLocation, "newLocation");
		lastEventId = eventId;
	}

	void recordEvent(long eventId) {
		lastEventId = eventId;
	}
}
