package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.DemographyConfig;
import com.thysummer.coopcraft.civilization.simulation.DemographySystem;
import com.thysummer.coopcraft.civilization.simulation.KnowledgeConfig;
import com.thysummer.coopcraft.civilization.simulation.KnowledgeSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationConfig;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SurvivalSystem;
import com.thysummer.coopcraft.civilization.simulation.WorkType;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
		if (!enabled) {
			state.setProductionEnabled(false);
			state.setGrowthEnabled(false);
			state.setSkillsEnabled(false);
			state.setNormsEnabled(false);
		}
		state.setSurvivalEnabled(enabled, currentDay(level));
		data.setDirty();
	}

	public static void setProductionEnabled(SettlementSavedData data, Settlement settlement, boolean enabled) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		if (enabled && !state.survivalEnabled()) {
			throw new IllegalStateException("Production requires survival simulation to be enabled");
		}
		if (state.productionEnabled() != enabled) {
			state.setProductionEnabled(enabled);
			data.setDirty();
		}
	}

	public static boolean isProductionEnabled(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement).map(SettlementSimulationState::productionEnabled).orElse(false);
	}

	public static void setGrowthEnabled(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		boolean enabled
	) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		if (enabled && !state.survivalEnabled()) {
			throw new IllegalStateException("Growth mapping requires survival simulation to be enabled");
		}
		if (state.growthEnabled() == enabled) {
			return;
		}
		state.setGrowthEnabled(enabled);
		if (enabled) {
			for (Map.Entry<UUID, Person> resident : activeResidents(level, data, settlement, state).entrySet()) {
				syncObservedGrowth(level, state, resident.getKey(), resident.getValue());
			}
		}
		data.setDirty();
	}

	public static boolean isGrowthEnabled(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement).map(SettlementSimulationState::growthEnabled).orElse(false);
	}

	public static void setSkillsEnabled(SettlementSavedData data, Settlement settlement, boolean enabled) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		if (enabled && !state.survivalEnabled()) {
			throw new IllegalStateException("Skill mapping requires survival simulation to be enabled");
		}
		if (state.skillsEnabled() != enabled) {
			state.setSkillsEnabled(enabled);
			data.setDirty();
		}
	}

	public static boolean isSkillsEnabled(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement).map(SettlementSimulationState::skillsEnabled).orElse(false);
	}

	public static void setNormsEnabled(SettlementSavedData data, Settlement settlement, boolean enabled) {
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		if (enabled && !state.survivalEnabled()) {
			throw new IllegalStateException("Norm mapping requires survival simulation to be enabled");
		}
		if (state.normsEnabled() != enabled) {
			state.setNormsEnabled(enabled);
			data.setDirty();
		}
	}

	public static boolean isNormsEnabled(SettlementSavedData data, Settlement settlement) {
		return data.simulationOf(settlement).map(SettlementSimulationState::normsEnabled).orElse(false);
	}

	public static boolean recordBirth(ServerLevel level, Villager firstParent, Villager secondParent, Villager child) {
		if (firstParent.isBaby() || secondParent.isBaby() || !child.isBaby()) {
			return false;
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement settlement = data.all().stream()
			.filter(candidate -> data.isMember(candidate, firstParent.getUUID()))
			.filter(candidate -> data.isMember(candidate, secondParent.getUUID()))
			.findFirst()
			.orElse(null);
		if (settlement == null) {
			return false;
		}
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		if (!state.mapResidentWithParents(child.getUUID(), data.needsOf(settlement, child.getUUID()).satiety(),
			firstParent.getUUID(), secondParent.getUUID())) {
			return false;
		}
		data.registerMember(settlement, child.getUUID(), currentDay(level));
		data.completeEraMilestone(settlement, CivilizationEras.POPULATION_GROWTH);
		VillagerNames.assignOrMigrate(data, settlement, child);
		data.setDirty();
		return true;
	}

	public static int gatherWorkDuration(
		SettlementSavedData data,
		Settlement settlement,
		UUID villagerId,
		int duration
	) {
		return workDuration(data, settlement, villagerId, duration, WorkType.GATHER);
	}

	public static int craftingWorkDuration(
		SettlementSavedData data,
		Settlement settlement,
		UUID villagerId,
		int duration
	) {
		return workDuration(data, settlement, villagerId, duration, WorkType.CRAFT);
	}

	private static int workDuration(
		SettlementSavedData data,
		Settlement settlement,
		UUID villagerId,
		int duration,
		WorkType workType
	) {
		if (duration <= 0) {
			throw new IllegalArgumentException("Work duration must be positive");
		}
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled()) {
			return duration;
		}
		String personId = state.residentId(villagerId).orElse(null);
		if (personId == null) {
			return duration;
		}
		Person person = state.world().person(personId);
		double effectiveSkill = state.skillsEnabled() ? Math.max(0.5, Math.min(2.0, person.skill(workType))) : 1.0;
		int availableHours = state.normsEnabled() ? Math.max(1, 6 - person.previousSocialHours()) : 6;
		return Math.max(1, (int) Math.round(duration * 6.0 / (effectiveSkill * availableHours)));
	}

	public static boolean hasGatherTime(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		return data.simulationOf(settlement)
			.filter(SettlementSimulationState::survivalEnabled)
			.filter(SettlementSimulationState::normsEnabled)
			.flatMap(state -> state.residentId(villagerId).map(state.world()::person))
			.map(person -> person.previousSocialHours() < 6)
			.orElse(true);
	}

	public static long craftingResourceCount(SettlementSavedData data, Settlement settlement, Item item) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.productionEnabled()) {
			return 0;
		}
		ResourceType resourceType = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		if (resourceType == null) {
			return 0;
		}
		return state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == resourceType)
			.mapToLong(batch -> batch.quantity())
			.sum();
	}

	public static int withdrawForCrafting(
		Villager villager,
		SettlementSavedData data,
		Settlement settlement,
		Item item,
		int requested,
		String recipe
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.productionEnabled() || requested <= 0) {
			return 0;
		}
		String personId = state.residentId(villager.getUUID()).orElse(null);
		ResourceType resourceType = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		if (personId == null || resourceType == null) {
			return 0;
		}
		int remaining = requested;
		int movedTotal = 0;
		for (var batch : state.world().batchesAt(state.campOwner())) {
			if (batch.resourceType() != resourceType) {
				continue;
			}
			int requestedFromBatch = (int) Math.min(remaining, batch.quantity());
			int moved = MinecraftStockpileAdapter.exportToContainer(
				state.world(), state.campOwner(), batch.batchId(), requestedFromBatch, villager.getInventory(),
				List.of(personId), Map.of(
					"activity", "craft", "recipe", recipe, "villagerId", villager.getUUID().toString()
				)
			);
			remaining -= moved;
			movedTotal += moved;
			if (moved == 0 || remaining == 0) {
				break;
			}
		}
		if (movedTotal > 0) {
			data.setDirty();
		}
		return movedTotal;
	}

	public static int recordProduction(
		Villager villager,
		SettlementSavedData data,
		Settlement settlement,
		Item item,
		int inserted
	) {
		return recordProduction(villager, data, settlement, item, inserted, "gather");
	}

	public static int recordProduction(
		Villager villager,
		SettlementSavedData data,
		Settlement settlement,
		Item item,
		int inserted,
		String activity
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || inserted <= 0) {
			return 0;
		}
		String personId = state.residentId(villager.getUUID()).orElse(null);
		if (personId == null) {
			return 0;
		}
		int moved = 0;
		long productionEventId = -1;
		ResourceType resourceType = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		if (state.productionEnabled() && resourceType != null) {
			moved = MinecraftStockpileAdapter.importToSimulation(
				villager.getInventory(), state.world(), state.campOwner(), resourceType, inserted,
				List.of(personId), Map.of("activity", activity, "villagerId", villager.getUUID().toString())
			);
			if (moved > 0) {
				productionEventId = state.world().ledger().events().getLast().eventId();
				data.setDirty();
			}
		}
		if (state.skillsEnabled()) {
			boolean practiced = knowledge(state).practice(
				state.world().person(personId), WorkType.GATHER,
				Map.of("activity", activity, "villagerId", villager.getUUID().toString()),
				productionEventId < 0 ? List.of() : List.of(productionEventId)
			);
			if (practiced) {
				data.setDirty();
			}
		}
		return moved;
	}

	public static boolean recordCrafting(
		Villager villager,
		SettlementSavedData data,
		Settlement settlement,
		String recipe
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.productionEnabled() && !state.skillsEnabled()) {
			return false;
		}
		String personId = state.residentId(villager.getUUID()).orElse(null);
		if (personId == null) {
			return false;
		}
		long previousPractice = state.world().ledger().events().stream()
			.filter(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.SKILL_PRACTICED)
			.filter(event -> event.actors().equals(List.of(personId)))
			.filter(event -> WorkType.CRAFT.name().equals(event.context().get("workType")))
			.mapToLong(SimulationEvent::eventId)
			.max().orElse(0);
		List<Long> materialCauses = state.world().ledger().events().stream()
			.filter(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.EXTERNAL_EXPORT)
			.filter(event -> event.eventId() > previousPractice)
			.filter(event -> event.actors().contains(personId))
			.filter(event -> "craft".equals(event.context().get("activity")))
			.filter(event -> recipe.equals(event.context().get("recipe")))
			.map(SimulationEvent::eventId)
			.toList();
		long completed = state.world().recordCrafting(
			personId, recipe, Map.of("activity", "craft", "villagerId", villager.getUUID().toString()), materialCauses
		);
		boolean practiced = state.skillsEnabled() && knowledge(state).practice(
			state.world().person(personId), WorkType.CRAFT,
			Map.of("activity", "craft", "recipe", recipe, "villagerId", villager.getUUID().toString()),
			List.of(completed)
		);
		data.setDirty();
		return practiced;
	}

	public static int recordCraftedProduct(
		Villager villager,
		SettlementSavedData data,
		Settlement settlement,
		String recipe,
		Item item,
		int inserted
	) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.productionEnabled() || inserted <= 0) {
			return 0;
		}
		String personId = state.residentId(villager.getUUID()).orElse(null);
		ResourceType resourceType = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		if (personId == null || resourceType == null) {
			return 0;
		}
		long completed = state.world().ledger().events().stream()
			.filter(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.CRAFT_COMPLETED)
			.filter(event -> event.actors().equals(List.of(personId)))
			.filter(event -> recipe.equals(event.context().get("recipe")))
			.mapToLong(SimulationEvent::eventId)
			.max().orElse(-1);
		if (completed < 0) {
			return 0;
		}
		int moved = MinecraftStockpileAdapter.importToSimulation(
			villager.getInventory(), state.world(), state.campOwner(), resourceType, inserted,
			List.of(personId), Map.of(
				"activity", "craft", "recipe", recipe, "villagerId", villager.getUUID().toString()
			),
			List.of(completed)
		);
		if (moved > 0) {
			data.setDirty();
		}
		return moved;
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
			if (state.normsEnabled()) {
				state.norms().startDay(active.values());
				state.norms().processSocialPhase(active.values(), true);
			}
			survival(state).advanceDay(active.values());
			for (UUID villagerId : active.keySet()) {
				syncSatiety(data, settlement, state, villagerId);
			}
			if (state.growthEnabled()) {
				for (Map.Entry<UUID, Person> resident : active.entrySet()) {
					growth(state).advanceObservedAgeDay(
						resident.getValue(), Map.of("source", "core_age_days", "villagerId", resident.getKey().toString())
					);
					syncObservedGrowth(level, state, resident.getKey(), resident.getValue());
				}
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

	public static boolean hasFoodFor(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled()) {
			return false;
		}
		String personId = state.residentId(villagerId).orElse(null);
		return personId != null && state.remainingFoodUnits(personId) > 0
			&& survival(state).availableFood(state.campOwner().id()) > 0;
	}

	public static int eat(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		return eat(data, settlement, villagerId, 0);
	}

	static int eat(SettlementSavedData data, Settlement settlement, UUID villagerId, long existingRequestEventId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled()) {
			return 0;
		}
		String personId = state.residentId(villagerId).orElse(null);
		if (personId == null) {
			return 0;
		}
		Person person = state.world().person(personId);
		int remainingUnits = state.remainingFoodUnits(personId);
		if (remainingUnits == 0) {
			recordFoodRefusal(data, state, person);
			return 0;
		}
		SurvivalSystem.MealResult meal = survival(state).eatFromCommon(person, remainingUnits, existingRequestEventId);
		if (meal.units() > 0) {
			state.recordFoodAllocation(personId, meal.units());
			if (state.normsEnabled()) {
				state.norms().recordCommonAllocation(
					person, meal.units(), meal.requestEventId(), meal.allocationEventId()
				);
			}
			syncSatiety(data, settlement, state, villagerId);
			data.setDirty();
		}
		return (int) Math.min(Integer.MAX_VALUE, meal.nutrition());
	}

	private static void recordFoodRefusal(SettlementSavedData data, SettlementSimulationState state, Person person) {
		if (!state.normsEnabled() || person.hunger() <= 5) {
			return;
		}
		boolean alreadyRefused = state.world().ledger().eventsAtDay(state.world().day()).stream()
			.anyMatch(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.SHARE_REFUSED
				&& event.actors().equals(List.of(person.id(), state.campOwner().id())));
		if (alreadyRefused) {
			return;
		}
		long request = state.world().recordShareRequest(person.id(), state.campOwner().id(), person.hunger() - 5L);
		state.world().recordShareRefusal(request, person.id(), state.campOwner().id(), 0);
		data.setDirty();
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

	private static DemographySystem growth(SettlementSimulationState state) {
		return new DemographySystem(state.world(), DemographyConfig.disabled());
	}

	private static KnowledgeSystem knowledge(SettlementSimulationState state) {
		return new KnowledgeSystem(state.world(), KnowledgeConfig.disabled());
	}

	private static void syncObservedGrowth(
		ServerLevel level,
		SettlementSimulationState state,
		UUID villagerId,
		Person person
	) {
		if (!(level.getEntity(villagerId) instanceof Villager villager)) {
			return;
		}
		if (!villager.isBaby() && person.ageBand() == Person.AgeBand.ELDER) {
			return;
		}
		Person.AgeBand observed = villager.isBaby() ? Person.AgeBand.CHILD : Person.AgeBand.ADULT;
		growth(state).synchronizeObservedAgeBand(
			person, observed, Map.of("source", "minecraft_villager", "villagerId", villagerId.toString())
		);
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
