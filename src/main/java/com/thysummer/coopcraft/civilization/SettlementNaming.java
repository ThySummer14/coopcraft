package com.thysummer.coopcraft.civilization;

import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class SettlementNaming {
	private static final String[] PREFIXES = {
		"Ash", "Birch", "Cedar", "Dawn", "Elm", "Fern", "Gold", "Hazel",
		"Mist", "Oak", "River", "Stone", "Sun", "West", "Willow", "Wind"
	};
	private static final String[] SUFFIXES = {
		"bury", "dale", "field", "ford", "haven", "hurst",
		"keep", "mere", "shire", "stead", "ton", "wick"
	};

	private SettlementNaming() {
	}

	static String forSettlement(ResourceKey<Level> dimension, BlockPos center) {
		long dimensionSeed = (long) dimension.location().toString().hashCode() << 32;
		return fromSeed(center.asLong() ^ dimensionSeed);
	}

	static String fromSeed(long seed) {
		Random random = new Random(seed);
		return PREFIXES[random.nextInt(PREFIXES.length)] + SUFFIXES[random.nextInt(SUFFIXES.length)];
	}
}
