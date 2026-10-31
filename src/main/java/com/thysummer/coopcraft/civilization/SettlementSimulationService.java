package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationConfig;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SurvivalSystem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

public final class SettlementSimulationService {
	private SettlementSimulationService() {
	}

	public static int importFromStockpile(
		SettlementSavedData data,
		Settlement settlement,
		Container container,
		ResourceType resourceType,
		int requested
	) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		int moved = MinecraftStockpileAdapter.importToSimulation(container, state.world(), state.campOwner(), resourceType, requested);
		if (moved > 0) {
			data.setDirty();
		}
		return moved;
	}

	public static int exportToStockpile(
		SettlementSavedData data,
		Settlement settlement,
		String batchId,
		int requested,
		Container container
	) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		int moved = MinecraftStockpileAdapter.exportToContainer(state.world(), state.campOwner(), batchId, requested, container);
		if (moved > 0) {
			data.setDirty();
		}
		return moved;
	}

	public static void setSurvivalEnabled(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		boolean enabled
	) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		if (state.survivalEnabled() == enabled) {
			return;
		}
		if (enabled && state.world().people().isEmpty()) {
			throw new IllegalStateException("Survival simulation requires at least one mapped resident");
		}
		if (enabled) {
			for (SettlementMember member : data.membersOf(settlement)) {
				state.residentId(member.villagerId()).ifPresent(personId -> state.world().person(personId)
					.setHunger(100 - data.needsOf(settlement, member.villagerId()).satiety()));
			}
		} else {
			for (UUID villagerId : state.residentMappings().keySet()) {
				syncSatiety(data, settlement, state, villagerId);
			}
		}
		state.setSurvivalEnabled(enabled, currentDay(level));
		data.setDirty();
	}

	public static boolean isSurvivalEnabled(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement).map(SettlementSimulationState::survivalEnabled).orElse(false);
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled()) {
			return;
		}
		long currentDay = currentDay(level);
		if (currentDay <= state.lastSurvivalDay()) {
			return;
		}
		Map<UUID, Person> active = activeResidents(level, data, settlement, state);
		if (!active.isEmpty()) {
			survival(state).advanceDay(active.values());
			for (UUID villagerId : active.keySet()) {
				syncSatiety(data, settlement, state, villagerId);
			}
		}
		state.markSurvivalDay(currentDay);
		data.setDirty();
	}

	public static boolean hasFood(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement)
			.filter(SettlementSimulationState::survivalEnabled)
			.map(state -> survival(state).availableFood(state.campOwner().id()) > 0)
			.orElse(false);
	}

	public static int eat(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled()) {
			return 0;
		}
		String personId = state.residentId(villagerId).orElse(null);
		if (personId == null) {
			return 0;
		}
		long consumed = survival(state).eatFromCommon(state.world().person(personId));
		if (consumed > 0) {
			syncSatiety(data, settlement, state, villagerId);
			data.setDirty();
		}
		return (int) Math.min(Integer.MAX_VALUE, consumed);
	}

	private static Map<UUID, Person> activeResidents(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state
	) {
		Map<UUID, Person> active = new LinkedHashMap<>();
		for (Map.Entry<UUID, String> entry : state.residentMappings().entrySet()) {
			UUID villagerId = entry.getKey();
			if (!data.isMember(settlement, villagerId)) {
				continue;
			}
			if (level.getEntity(villagerId) instanceof Villager villager
				&& villager.isAlive()
				&& villager.distanceToSqr(settlement.center().getCenter()) <= 64.0 * 64.0) {
				active.put(villagerId, state.world().person(entry.getValue()));
			}
		}
		return active;
	}

	private static SurvivalSystem survival(SettlementSimulationState state) {
		return new SurvivalSystem(state.world(), SimulationConfig.defaults());
	}

	private static void syncSatiety(
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		UUID villagerId
	) {
		if (!data.isMember(settlement, villagerId)) {
			return;
		}
		state.residentId(villagerId).ifPresent(personId -> data.setSatiety(
			settlement, villagerId, 100 - state.world().person(personId).hunger()
		));
	}

	private static long currentDay(ServerLevel level) {
		return level.getDayTime() / Level.TICKS_PER_DAY;
	}

	public static List<SimulationEvent> recentEvents(SettlementSavedData data, Settlement settlement, int limit) {
		return data.simulationOf(settlement).map(state -> state.recentEvents(limit)).orElse(List.of());
	}
}
