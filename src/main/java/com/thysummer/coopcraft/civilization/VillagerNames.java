package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;

public final class VillagerNames {
	private static final String[] GIVEN_NAMES = {
		"Aren", "Bela", "Cato", "Dara", "Eli", "Fara", "Galen", "Hana",
		"Ivo", "Jora", "Kai", "Lena", "Mira", "Nero", "Orin", "Pia",
		"Quin", "Rhea", "Soren", "Tara", "Ulla", "Vero", "Wren", "Yara", "Zeno"
	};
	private static final String[] FAMILY_NAMES = {
		"Ash", "Birch", "Brook", "Clay", "Dale", "Flint", "Grove", "Hollow",
		"Marsh", "Reed", "Stone", "Vale", "Willow", "Wren", "Field", "Frost"
	};

	private VillagerNames() {
	}

	public static String fullName(UUID villagerId) {
		return givenName(villagerId) + " " + familyName(villagerId);
	}

	public static String givenName(UUID villagerId) {
		long seed = villagerId.getMostSignificantBits() ^ Long.rotateLeft(villagerId.getLeastSignificantBits(), 17);
		return GIVEN_NAMES[hash(seed, GIVEN_NAMES.length)];
	}

	public static String familyName(UUID villagerId) {
		long seed = Long.rotateLeft(villagerId.getMostSignificantBits(), 13) ^ villagerId.getLeastSignificantBits();
		return FAMILY_NAMES[hash(seed, FAMILY_NAMES.length)];
	}

	public static String nameFor(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		String family = familyName(villagerId);
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state != null) {
			String personId = state.residentId(villagerId).orElse(null);
			if (personId != null) {
				Person person = state.world().person(personId);
				if (!person.parents().isEmpty()) {
					String parentPersonId = person.parents().getFirst();
					UUID parentVillagerId = state.residentMappings().entrySet().stream()
						.filter(entry -> entry.getValue().equals(parentPersonId))
						.map(Map.Entry::getKey)
						.findFirst()
						.orElse(null);
					if (parentVillagerId != null) {
						family = familyName(parentVillagerId);
					}
				}
			}
		}
		return givenName(villagerId) + " " + family;
	}

	static void assignIfAbsent(SettlementSavedData data, Settlement settlement, Villager villager) {
		if (villager.getCustomName() != null) {
			return;
		}
		villager.setCustomName(Component.literal(nameFor(data, settlement, villager.getUUID())));
	}

	private static int hash(long seed, int bound) {
		int mixed = (int) (seed ^ (seed >>> 32));
		mixed ^= mixed >>> 16;
		return Math.floorMod(mixed, bound);
	}
}
