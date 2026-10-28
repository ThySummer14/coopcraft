package com.thysummer.coopcraft.civilization.simulation;

import java.util.Objects;

public final class ResourcePatch {
	private final String id;
	private final String campId;
	private final Position position;
	private final String biome;
	private final ResourceType resourceType;
	private final long carryingCapacity;
	private final double naturalGrowthRate;
	private long stock;
	private long lastHarvestedDay;

	public ResourcePatch(
		String id,
		String campId,
		Position position,
		String biome,
		ResourceType resourceType,
		long stock,
		long carryingCapacity,
		double naturalGrowthRate
	) {
		if (id.isBlank() || campId.isBlank() || biome.isBlank()) {
			throw new IllegalArgumentException("Patch, camp and biome IDs cannot be blank");
		}
		if (carryingCapacity <= 0 || stock < 0 || stock > carryingCapacity) {
			throw new IllegalArgumentException("Patch stock must be within its carrying capacity");
		}
		if (!Double.isFinite(naturalGrowthRate) || naturalGrowthRate < 0) {
			throw new IllegalArgumentException("Growth rate must be finite and nonnegative");
		}
		this.id = id;
		this.campId = campId;
		this.position = Objects.requireNonNull(position, "position");
		this.biome = biome;
		this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
		this.stock = stock;
		this.carryingCapacity = carryingCapacity;
		this.naturalGrowthRate = naturalGrowthRate;
		this.lastHarvestedDay = Long.MIN_VALUE;
	}

	public String id() {
		return id;
	}

	public String campId() {
		return campId;
	}

	public Position position() {
		return position;
	}

	public String biome() {
		return biome;
	}

	public ResourceType resourceType() {
		return resourceType;
	}

	public long stock() {
		return stock;
	}

	public long carryingCapacity() {
		return carryingCapacity;
	}

	public double naturalGrowthRate() {
		return naturalGrowthRate;
	}

	public long lastHarvestedDay() {
		return lastHarvestedDay;
	}

	long harvest(long requested, long day) {
		if (requested < 0) {
			throw new IllegalArgumentException("Harvest cannot be negative");
		}
		long harvested = Math.min(stock, requested);
		stock -= harvested;
		if (harvested > 0) {
			lastHarvestedDay = day;
		}
		return harvested;
	}

	long regenerate(double seasonMultiplier) {
		if (!Double.isFinite(seasonMultiplier) || seasonMultiplier < 0) {
			throw new IllegalArgumentException("Season multiplier must be finite and nonnegative");
		}
		if (stock == 0 || stock == carryingCapacity || naturalGrowthRate == 0 || seasonMultiplier == 0) {
			return 0;
		}
		double growth = naturalGrowthRate * stock * (1.0 - (double) stock / carryingCapacity) * seasonMultiplier;
		long regenerated = Math.min(carryingCapacity - stock, Math.max(1, Math.round(growth)));
		stock += regenerated;
		return regenerated;
	}

	public record Position(long x, long y, long z) {
	}
}
