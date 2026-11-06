package com.thysummer.coopcraft.civilization;

import net.minecraft.nbt.CompoundTag;

public record ProductionStats(
	long cycles,
	long meanCycleTicks,
	int observedIntervals,
	int recommendedIntervalTicks,
	long lastSuccessTick,
	long baselineCycleTicks,
	long improvedCycleTicks,
	int improvedSamples
) {
	public static final int DEFAULT_INTERVAL = 40;

	public ProductionStats {
		if (cycles < 0 || meanCycleTicks < 0 || observedIntervals < 0 || recommendedIntervalTicks <= 0
			|| baselineCycleTicks < 0 || improvedCycleTicks < 0 || improvedSamples < 0) {
			throw new IllegalArgumentException("Invalid production statistics");
		}
	}

	public static ProductionStats initial() {
		return new ProductionStats(0, 0, 0, DEFAULT_INTERVAL, -1, 0, 0, 0);
	}

	public ProductionStats observe(long gameTime) {
		if (gameTime < 0 || lastSuccessTick >= 0 && gameTime < lastSuccessTick) {
			throw new IllegalArgumentException("Production observations must move forward in time");
		}
		if (lastSuccessTick < 0) {
			return new ProductionStats(1, 0, 0, recommendedIntervalTicks, gameTime, 0, 0, 0);
		}
		long interval = Math.max(1, gameTime - lastSuccessTick);
		long mean = observedIntervals == 0 ? interval : (meanCycleTicks * 3 + interval) / 4;
		int observed = observedIntervals + 1;
		int recommended = recommendedIntervalTicks;
		if (cycles + 1 >= 3 && mean >= recommended * 2L && recommended > 20) {
			recommended /= 2;
		} else if (cycles + 1 >= 3 && mean < recommended / 2L && recommended < 80) {
			recommended *= 2;
		}
		long baseline = baselineCycleTicks;
		if (recommended != recommendedIntervalTicks && baseline == 0) {
			baseline = mean;
		}
		long improved = improvedSamples == 0 ? interval : (improvedCycleTicks + interval) / 2;
		int samples = baselineCycleTicks == 0 ? 0 : improvedSamples + 1;
		return new ProductionStats(cycles + 1, mean, observed, recommended, gameTime, baseline, improved, samples);
	}

	public boolean adaptiveLearned() {
		return cycles >= 3 && recommendedIntervalTicks != DEFAULT_INTERVAL;
	}

	public boolean selfImproved() {
		return baselineCycleTicks > 0 && improvedSamples >= 2 && improvedCycleTicks < baselineCycleTicks;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putLong("Cycles", cycles);
		tag.putLong("MeanCycleTicks", meanCycleTicks);
		tag.putInt("ObservedIntervals", observedIntervals);
		tag.putInt("RecommendedIntervalTicks", recommendedIntervalTicks);
		tag.putLong("LastSuccessTick", lastSuccessTick);
		tag.putLong("BaselineCycleTicks", baselineCycleTicks);
		tag.putLong("ImprovedCycleTicks", improvedCycleTicks);
		tag.putInt("ImprovedSamples", improvedSamples);
		return tag;
	}

	public static ProductionStats load(CompoundTag tag) {
		return new ProductionStats(
			tag.getLong("Cycles"),
			tag.getLong("MeanCycleTicks"),
			tag.getInt("ObservedIntervals"),
			tag.getInt("RecommendedIntervalTicks"),
			tag.getLong("LastSuccessTick"),
			tag.getLong("BaselineCycleTicks"),
			tag.getLong("ImprovedCycleTicks"),
			tag.getInt("ImprovedSamples")
		);
	}
}
