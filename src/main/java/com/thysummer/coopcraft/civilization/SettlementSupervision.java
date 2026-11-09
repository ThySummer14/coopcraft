package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.thysummer.coopcraft.civilization.simulation.Person;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Read-only, instantaneous loaded-camp attendance. Never loads chunks or persists presence. */
public final class SettlementSupervision {
	private SettlementSupervision() {}

	record Presence(boolean alive, boolean loaded, boolean entityTicking, boolean sameLevel,
		boolean adult, double distanceSquared) {
		boolean eligible() {
			return alive && loaded && entityTicking && sameLevel && adult
				&& Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 64 * 64;
		}
	}

	public static List<Person> candidates(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		if (level == null || data == null || settlement == null || SettlementSavedData.get(level) != data) return List.of();
		return candidates(data, settlement, id -> {
			if (!(level.getEntity(id) instanceof Villager villager)) return null;
			return new Presence(villager.isAlive() && !villager.isRemoved(), level.isLoaded(villager.blockPosition()),
				level.isPositionEntityTicking(villager.blockPosition()), villager.level() == level,
				!villager.isBaby(), villager.distanceToSqr(settlement.center().getCenter()));
		});
	}

	/** Shared identity gate for real observations and synthetic presence tests. */
	static List<Person> candidates(SettlementSavedData data, Settlement settlement, Function<UUID, Presence> observe) {
		if (data == null || settlement == null || observe == null
			|| data.all().stream().noneMatch(canonical -> canonical == settlement)) return List.of();
		var state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.normsEnabled()) return List.of();
		var living = state.world().people().stream().filter(person -> person.campId().equals(state.campOwner().id()))
			.collect(java.util.stream.Collectors.toMap(Person::id, Function.identity()));
		return state.residentMappings().entrySet().stream()
			.filter(entry -> data.isMember(settlement, entry.getKey()))
			.filter(entry -> living.containsKey(entry.getValue()))
			.filter(entry -> { Presence presence = observe.apply(entry.getKey()); return presence != null && presence.eligible(); })
			.map(entry -> living.get(entry.getValue()))
			.distinct().toList();
	}
}
