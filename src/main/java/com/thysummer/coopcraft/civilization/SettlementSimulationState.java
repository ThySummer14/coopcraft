package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Camp;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Household;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SimulationWorld;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

public final class SettlementSimulationState {
	private final Settlement settlement;
	private final SimulationWorld world;
	private final Map<UUID, String> residentIds = new LinkedHashMap<>();
	private boolean survivalEnabled;
	private long lastSurvivalDay = Long.MIN_VALUE;

	private SettlementSimulationState(Settlement settlement, SimulationWorld world) {
		this.settlement = settlement;
		this.world = world;
	}

	public static SettlementSimulationState create(Settlement settlement) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp(campId(settlement), settlement.name()));
		return new SettlementSimulationState(settlement, world);
	}

	public SimulationWorld world() {
		return world;
	}

	public StorageOwner campOwner() {
		return StorageOwner.camp(campId(settlement));
	}

	public Optional<String> residentId(UUID villagerId) {
		return Optional.ofNullable(residentIds.get(villagerId));
	}

	public Map<UUID, String> residentMappings() {
		return Map.copyOf(residentIds);
	}

	public boolean survivalEnabled() {
		return survivalEnabled;
	}

	public long lastSurvivalDay() {
		return lastSurvivalDay;
	}

	public void setSurvivalEnabled(boolean enabled, long currentDay) {
		survivalEnabled = enabled;
		lastSurvivalDay = currentDay;
	}

	public void markSurvivalDay(long currentDay) {
		lastSurvivalDay = currentDay;
	}

	public boolean mapResident(UUID villagerId, boolean child, int satiety) {
		if (residentIds.containsKey(villagerId)) {
			return false;
		}
		String householdId = householdId(villagerId);
		String personId = personId(villagerId);
		world.registerHousehold(new Household(householdId, campId(settlement)));
		Person person = new Person(
			personId,
			campId(settlement),
			householdId,
			child ? Person.AgeBand.CHILD : Person.AgeBand.ADULT,
			100 - Math.max(0, Math.min(100, satiety)),
			50
		);
		world.registerPerson(person);
		residentIds.put(villagerId, personId);
		return true;
	}

	public List<SimulationEvent> recentEvents(int limit) {
		if (limit < 0) {
			throw new IllegalArgumentException("Event limit cannot be negative");
		}
		List<SimulationEvent> events = world.ledger().events();
		int fromIndex = Math.max(0, events.size() - limit);
		return List.copyOf(events.subList(fromIndex, events.size()));
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putLong("Day", world.day());
		tag.putLong("NextBatchId", world.nextBatchId());
		tag.putBoolean("SurvivalEnabled", survivalEnabled);
		tag.putLong("LastSurvivalDay", lastSurvivalDay);
		ListTag residents = new ListTag();
		for (Map.Entry<UUID, String> entry : residentIds.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
			.toList()) {
			Person person = world.person(entry.getValue());
			CompoundTag resident = new CompoundTag();
			resident.putUUID("VillagerId", entry.getKey());
			resident.putString("PersonId", person.id());
			resident.putString("HouseholdId", person.householdId());
			resident.putString("AgeBand", person.ageBand().name());
			resident.putInt("Hunger", person.hunger());
			resident.putInt("RiskPreference", person.riskPreference());
			residents.add(resident);
		}
		tag.put("Residents", residents);
		ListTag batches = new ListTag();
		for (ResourceBatch batch : world.batches()) {
			batches.add(saveBatch(batch));
		}
		tag.put("Batches", batches);
		ListTag events = new ListTag();
		for (SimulationEvent event : world.ledger().events()) {
			events.add(saveEvent(event));
		}
		tag.put("Events", events);
		return tag;
	}

	static SettlementSimulationState load(Settlement settlement, CompoundTag tag) {
		SettlementSimulationState state = create(settlement);
		state.survivalEnabled = tag.contains("SurvivalEnabled") && tag.getBoolean("SurvivalEnabled");
		state.lastSurvivalDay = tag.contains("LastSurvivalDay") ? tag.getLong("LastSurvivalDay") : Long.MIN_VALUE;
		ListTag residents = tag.getList("Residents", ListTag.TAG_COMPOUND);
		for (int i = 0; i < residents.size(); i++) {
			CompoundTag resident = residents.getCompound(i);
			String householdId = resident.getString("HouseholdId");
			if (state.world.allPeople().stream().noneMatch(person -> person.householdId().equals(householdId))) {
				state.world.registerHousehold(new Household(householdId, campId(settlement)));
			}
			Person person = new Person(
				resident.getString("PersonId"),
				campId(settlement),
				householdId,
				Person.AgeBand.valueOf(resident.getString("AgeBand")),
				resident.getInt("Hunger"),
				resident.getInt("RiskPreference")
			);
			state.world.registerPerson(person);
			state.residentIds.put(resident.getUUID("VillagerId"), person.id());
		}
		List<SimulationWorld.RestoredBatch> batches = new ArrayList<>();
		ListTag batchList = tag.getList("Batches", ListTag.TAG_COMPOUND);
		for (int i = 0; i < batchList.size(); i++) {
			batches.add(loadBatch(batchList.getCompound(i)));
		}
		List<SimulationEvent> events = new ArrayList<>();
		ListTag eventList = tag.getList("Events", ListTag.TAG_COMPOUND);
		for (int i = 0; i < eventList.size(); i++) {
			events.add(loadEvent(eventList.getCompound(i)));
		}
		state.world.restoreState(tag.getLong("Day"), tag.getLong("NextBatchId"), batches, events);
		return state;
	}

	private static CompoundTag saveBatch(ResourceBatch batch) {
		CompoundTag tag = new CompoundTag();
		tag.putString("BatchId", batch.batchId());
		tag.putString("ResourceType", batch.resourceType().name());
		tag.putLong("Quantity", batch.quantity());
		tag.putLong("NutritionPerUnit", batch.nutritionPerUnit());
		tag.putInt("Quality", batch.quality());
		tag.putString("HolderScope", batch.holder().scope().name());
		tag.putString("HolderId", batch.holder().id());
		tag.putString("Location", batch.location());
		if (batch.sourcePatchId() != null) {
			tag.putString("SourcePatchId", batch.sourcePatchId());
		}
		tag.putLong("OriginEventId", batch.originEventId());
		tag.put("ParentBatchIds", saveStrings(batch.parentBatchIds().stream().sorted().toList()));
		tag.putLong("HarvestDay", batch.harvestDay());
		tag.putLong("ExpiryDay", batch.expiryDay());
		tag.putLong("LastEventId", batch.lastEventId());
		return tag;
	}

	private static SimulationWorld.RestoredBatch loadBatch(CompoundTag tag) {
		return new SimulationWorld.RestoredBatch(
			tag.getString("BatchId"),
			ResourceType.valueOf(tag.getString("ResourceType")),
			tag.getLong("Quantity"),
			tag.getLong("NutritionPerUnit"),
			tag.getInt("Quality"),
			new StorageOwner(StorageOwner.Scope.valueOf(tag.getString("HolderScope")), tag.getString("HolderId")),
			tag.getString("Location"),
			tag.contains("SourcePatchId") ? tag.getString("SourcePatchId") : null,
			tag.getLong("OriginEventId"),
			Set.copyOf(loadStrings(tag.getList("ParentBatchIds", ListTag.TAG_STRING))),
			tag.getLong("HarvestDay"),
			tag.getLong("ExpiryDay"),
			tag.getLong("LastEventId")
		);
	}

	private static CompoundTag saveEvent(SimulationEvent event) {
		CompoundTag tag = new CompoundTag();
		tag.putLong("EventId", event.eventId());
		tag.putLong("Day", event.day());
		tag.putString("Type", event.type().name());
		tag.put("Actors", saveStrings(event.actors()));
		ListTag inputs = new ListTag();
		for (ResourceFlow input : event.inputs()) {
			inputs.add(saveFlow(input));
		}
		tag.put("Inputs", inputs);
		ListTag outputs = new ListTag();
		for (ResourceFlow output : event.outputs()) {
			outputs.add(saveFlow(output));
		}
		tag.put("Outputs", outputs);
		ListTag context = new ListTag();
		for (Map.Entry<String, String> entry : event.context().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.toList()) {
			CompoundTag value = new CompoundTag();
			value.putString("Key", entry.getKey());
			value.putString("Value", entry.getValue());
			context.add(value);
		}
		tag.put("Context", context);
		tag.putLongArray("CausalLinks", event.causalLinks().stream().mapToLong(Long::longValue).toArray());
		return tag;
	}

	private static SimulationEvent loadEvent(CompoundTag tag) {
		List<ResourceFlow> inputs = new ArrayList<>();
		ListTag inputList = tag.getList("Inputs", ListTag.TAG_COMPOUND);
		for (int i = 0; i < inputList.size(); i++) {
			inputs.add(loadFlow(inputList.getCompound(i)));
		}
		List<ResourceFlow> outputs = new ArrayList<>();
		ListTag outputList = tag.getList("Outputs", ListTag.TAG_COMPOUND);
		for (int i = 0; i < outputList.size(); i++) {
			outputs.add(loadFlow(outputList.getCompound(i)));
		}
		Map<String, String> context = new LinkedHashMap<>();
		ListTag contextList = tag.getList("Context", ListTag.TAG_COMPOUND);
		for (int i = 0; i < contextList.size(); i++) {
			CompoundTag value = contextList.getCompound(i);
			context.put(value.getString("Key"), value.getString("Value"));
		}
		List<Long> causalLinks = new ArrayList<>();
		for (long causalLink : tag.getLongArray("CausalLinks")) {
			causalLinks.add(causalLink);
		}
		return new SimulationEvent(
			tag.getLong("EventId"),
			tag.getLong("Day"),
			EventType.valueOf(tag.getString("Type")),
			loadStrings(tag.getList("Actors", ListTag.TAG_STRING)),
			inputs,
			outputs,
			context,
			causalLinks
		);
	}

	private static CompoundTag saveFlow(ResourceFlow flow) {
		CompoundTag tag = new CompoundTag();
		if (flow.batchId() != null) {
			tag.putString("BatchId", flow.batchId());
		}
		tag.putString("ResourceType", flow.resourceType().name());
		tag.putLong("Quantity", flow.quantity());
		tag.putLong("NutritionPerUnit", flow.nutritionPerUnit());
		if (flow.fromLocation() != null) {
			tag.putString("FromLocation", flow.fromLocation());
		}
		if (flow.toLocation() != null) {
			tag.putString("ToLocation", flow.toLocation());
		}
		return tag;
	}

	private static ResourceFlow loadFlow(CompoundTag tag) {
		return new ResourceFlow(
			tag.contains("BatchId") ? tag.getString("BatchId") : null,
			ResourceType.valueOf(tag.getString("ResourceType")),
			tag.getLong("Quantity"),
			tag.getLong("NutritionPerUnit"),
			tag.contains("FromLocation") ? tag.getString("FromLocation") : null,
			tag.contains("ToLocation") ? tag.getString("ToLocation") : null
		);
	}

	private static ListTag saveStrings(List<String> values) {
		ListTag tag = new ListTag();
		for (String value : values) {
			tag.add(StringTag.valueOf(value));
		}
		return tag;
	}

	private static List<String> loadStrings(ListTag tag) {
		List<String> values = new ArrayList<>();
		for (int i = 0; i < tag.size(); i++) {
			values.add(tag.getString(i));
		}
		return values;
	}

	private static String campId(Settlement settlement) {
		return settlement.id().toString();
	}

	private static String personId(UUID villagerId) {
		return "minecraft-person:" + villagerId;
	}

	private static String householdId(UUID villagerId) {
		return "minecraft-household:" + villagerId;
	}
}
