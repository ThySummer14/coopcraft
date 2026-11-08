package com.thysummer.coopcraft.civilization;

/** Tool ingredient demand from current capabilities, with no inventory or world mutations. */
public final class VillagerToolDemand {
	private VillagerToolDemand() {
	}

	/** The caller resolves inventory auto-equipping and validates the real station before taking this snapshot. */
	public static boolean needsSticks(HandState hand, boolean grindingStationAvailable, long availableSticks) {
		if (availableSticks >= 2) {
			return false;
		}
		return switch (hand) {
			case EMPTY -> true;
			case STONE_PICKAXE -> grindingStationAvailable;
			case POLISHED_STONE_PICKAXE, OCCUPIED -> false;
		};
	}

	public enum HandState {
		EMPTY,
		STONE_PICKAXE,
		POLISHED_STONE_PICKAXE,
		OCCUPIED
	}
}
