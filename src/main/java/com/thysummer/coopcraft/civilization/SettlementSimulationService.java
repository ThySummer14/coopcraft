package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Optional;
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

import net.minecraft.core.registries.BuiltInRegistries;
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
		VillagerNames.assignIfAbsent(data, settlement, child);
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
		return recordProduction(data, settlement, villager.getUUID(), villager.getInventory(), item, inserted, activity);
	}

	/** Container boundary shared by real collection and server-free service regression tests. */
	static int recordProduction(SettlementSavedData data, Settlement settlement, UUID actor, Container inventory,
		Item item, int inserted, String activity) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || inserted <= 0) {
			return 0;
		}
		String personId = state.residentId(actor).orElse(null);
		if (personId == null) {
			return 0;
		}
		int moved = 0;
		long productionEventId = -1;
		ResourceType resourceType = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		if (state.productionEnabled() && resourceType != null) {
			moved = MinecraftStockpileAdapter.importToSimulation(
				inventory, state.world(), state.campOwner(), resourceType, inserted,
				List.of(personId), Map.of("activity", activity, "villagerId", actor.toString())
			);
			if (moved > 0) {
				productionEventId = state.world().ledger().lastEvent().orElseThrow().eventId();
				data.setDirty();
			}
		}
		if (state.skillsEnabled()) {
			boolean practiced = knowledge(state).practice(
				state.world().person(personId), WorkType.GATHER,
				Map.of("activity", activity, "villagerId", actor.toString()),
				productionEventId < 0 ? List.of() : List.of(productionEventId)
			);
			if (practiced) {
				data.setDirty();
			}
		}
		return moved;
	}

	/** Ephemeral boundary for one synchronous server-thread crafting operation, never persisted. */
	public static final class CraftingAttempt {
		private final SettlementSimulationState state;
		private final com.thysummer.coopcraft.civilization.simulation.SimulationWorld world;
		private final UUID actor;
		private final String personId;
		private final String recipe;
		private final Item output;
		private final int produced;
		private final long watermark;
		private boolean completed;

		private CraftingAttempt(SettlementSimulationState state, UUID actor, String personId,
			String recipe, Item output, int produced) {
			this.state = state;
			this.world = state == null ? null : state.world();
			this.actor = actor;
			this.personId = personId;
			this.recipe = recipe;
			this.output = output;
			this.produced = produced;
			this.watermark = world == null ? 0 : world.ledger().lastEvent().map(SimulationEvent::eventId).orElse(0L);
		}
	}

	/** Unforgeable in-memory receipt; product import must use this exact completion. */
	public static final class CraftingCompletion {
		private final CraftingAttempt attempt;
		private final long eventId;
		private boolean imported;

		private CraftingCompletion(CraftingAttempt attempt, long eventId) {
			this.attempt = attempt;
			this.eventId = eventId;
		}

		public long eventId() {
			return eventId;
		}
	}

	public static CraftingAttempt beginCrafting(SettlementSavedData data, Settlement settlement,
		UUID actor, String recipe, Item output, int produced) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		String personId = state == null ? null : state.residentId(actor).orElse(null);
		if (state == null || !state.survivalEnabled() || (!state.productionEnabled() && !state.skillsEnabled())
			|| personId == null || !state.world().person(personId).isAlive()
			|| !data.isMember(settlement, actor) || recipe == null || recipe.isBlank() || output == null || produced <= 0) {
			state = null;
		}
		return new CraftingAttempt(state, actor, personId, recipe, output, produced);
	}

	private static boolean validCraftingAttempt(SettlementSavedData data, Settlement settlement,
		UUID actor, CraftingAttempt attempt) {
		if (attempt == null || attempt.state == null || !attempt.actor.equals(actor)) {
			return false;
		}
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		return state == attempt.state && state.world() == attempt.world && state.survivalEnabled()
			&& (state.productionEnabled() || state.skillsEnabled()) && data.isMember(settlement, actor)
			&& state.residentId(actor).filter(attempt.personId::equals).isPresent()
			&& state.world().person(attempt.personId).isAlive();
	}

	public static Optional<CraftingCompletion> recordCrafting(Villager villager, SettlementSavedData data,
		Settlement settlement, CraftingAttempt attempt) {
		return villager.isAlive() ? recordCrafting(data, settlement, villager.getUUID(), attempt) : Optional.empty();
	}

	static Optional<CraftingCompletion> recordCrafting(SettlementSavedData data, Settlement settlement,
		UUID actor, CraftingAttempt attempt) {
		if (!validCraftingAttempt(data, settlement, actor, attempt) || attempt.completed) {
			return Optional.empty();
		}
		SettlementSimulationState state = attempt.state;
		List<Long> causes = new ArrayList<>();
		long end = state.world().ledger().lastEvent().map(SimulationEvent::eventId).orElse(0L);
		// Only direct exports after this attempt began. Pre-existing real inventory has unknown provenance.
		for (long id = attempt.watermark + 1; id <= end; id++) {
			SimulationEvent event = state.world().ledger().event(id).orElseThrow();
			if (event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.EXTERNAL_EXPORT
				&& event.actors().equals(List.of(attempt.personId))
				&& "craft".equals(event.context().get("activity"))
				&& attempt.recipe.equals(event.context().get("recipe"))) {
				causes.add(id);
			}
		}
		long completed = state.world().recordCrafting(attempt.personId, attempt.recipe, Map.of(
			"activity", "craft", "villagerId", actor.toString(),
			"provenanceScope", "current_attempt_direct_exports", "preexistingInventoryProvenance", "unknown",
			"outputItem", BuiltInRegistries.ITEM.getKey(attempt.output).toString(),
			"outputCount", Integer.toString(attempt.produced)), causes);
		attempt.completed = true;
		data.setDirty();
		if (state.skillsEnabled()) {
			knowledge(state).practice(state.world().person(attempt.personId), WorkType.CRAFT,
				Map.of("activity", "craft", "recipe", attempt.recipe, "villagerId", actor.toString()), List.of(completed));
		}
		return Optional.of(new CraftingCompletion(attempt, completed));
	}

	public static int recordCraftedProduct(Villager villager, SettlementSavedData data, Settlement settlement,
		CraftingCompletion completion, Item item, int inserted) {
		return villager.isAlive() ? recordCraftedProduct(data, settlement, villager.getUUID(),
			villager.getInventory(), completion, item, inserted) : 0;
	}

	static int recordCraftedProduct(SettlementSavedData data, Settlement settlement, UUID actor,
		Container inventory, CraftingCompletion completion, Item item, int inserted) {
		if (completion == null || completion.imported || !validCraftingAttempt(data, settlement, actor, completion.attempt)) {
			return 0;
		}
		CraftingAttempt attempt = completion.attempt;
		if (inventory == null || !attempt.state.productionEnabled() || item != attempt.output || inserted <= 0 || inserted > attempt.produced) {
			return 0;
		}
		ResourceType type = MinecraftStockpileAdapter.resourceType(new ItemStack(item)).orElse(null);
		if (type == null) {
			return 0;
		}
		// Consume before handoff: a zero move or failure cannot authorize a later unrelated inventory.
		completion.imported = true;
		int moved = MinecraftStockpileAdapter.importToSimulation(inventory, attempt.world, attempt.state.campOwner(),
			type, inserted, List.of(attempt.personId),
			Map.of("activity", "craft", "recipe", attempt.recipe, "villagerId", actor.toString()), List.of(completion.eventId));
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

	/** Read-only eligibility; the request is recorded only after the villager reaches the store. */
	public static boolean hasPendingFoodRefusal(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null || !state.survivalEnabled() || !state.normsEnabled()) {
			return false;
		}
		return state.residentId(villagerId)
			.map(personId -> hasPendingFoodRefusal(state, state.world().person(personId)))
			.orElse(false);
	}

	private static boolean hasPendingFoodRefusal(SettlementSimulationState state, Person person) {
		return state.survivalEnabled() && state.normsEnabled() && person.isAlive()
			&& person.hunger() > 5 && state.remainingFoodUnits(person.id()) == 0
			&& !hasFoodRefusalToday(state, person);
	}

	private static boolean hasFoodRefusalToday(SettlementSimulationState state, Person person) {
		return state.world().ledger().eventsAtDay(state.world().day()).stream()
			.anyMatch(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.SHARE_REFUSED
				&& event.actors().equals(List.of(person.id(), state.campOwner().id())));
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
		Person person = state.world().person(personId);
		int remainingUnits = state.remainingFoodUnits(personId);
		if (remainingUnits == 0) {
			recordFoodRefusal(data, state, person);
			return 0;
		}
		SurvivalSystem.MealWithEvidence evidence = survival(state).eatFromCommonWithEvidence(person, remainingUnits);
		SurvivalSystem.MealResult meal = evidence.meal();
		if (meal.units() > 0) {
			// Consumption has already changed the world, even if bookkeeping below fails.
			data.setDirty();
			state.recordFoodAllocation(personId, meal.units());
			if (state.normsEnabled()) {
				state.norms().recordCommonAllocation(person, evidence, state.allocatedFoodUnits(personId));
			}
			syncSatiety(data, settlement, state, villagerId);
		}
		return (int) Math.min(Integer.MAX_VALUE, meal.nutrition());
	}

	private static void recordFoodRefusal(SettlementSavedData data, SettlementSimulationState state, Person person) {
		if (!hasPendingFoodRefusal(state, person)) {
			return;
		}
		long neededNutrition = person.hunger() - 5L;
		// Snapshot the current item allowance and abstract common food, not actual hunger relief.
		Map<String, String> context = Map.of(
			"reason", "daily-unit-cap-exhausted",
			"allocationBasis", "item_units",
			"allocatedUnits", Integer.toString(state.allocatedFoodUnits(person.id())),
			"cap", Integer.toString(state.dailyFoodUnitCap(person.id())),
			"hunger", Integer.toString(person.hunger()),
			"neededNutrition", Long.toString(neededNutrition),
			"availableNutrition", Long.toString(state.world().availableNutrition(state.campOwner()))
		);
		long request = state.world().recordShareRequest(person.id(), state.campOwner().id(), neededNutrition);
		state.world().recordShareRefusal(request, person.id(), state.campOwner().id(), 0, context);
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
