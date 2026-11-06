package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.thysummer.coopcraft.CoopCraft;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

public final class CivilizationEras {
	public static final ResourceLocation PRIMITIVE = CoopCraft.id("primitive");
	public static final ResourceLocation AGRICULTURAL = CoopCraft.id("agricultural");
	public static final ResourceLocation INDUSTRIAL = CoopCraft.id("industrial");
	public static final ResourceLocation INFORMATION = CoopCraft.id("information");
	public static final ResourceLocation INTELLIGENT = CoopCraft.id("intelligent");
	public static final ResourceLocation COMMON_STOCKPILE = milestone("common_stockpile");
	public static final ResourceLocation HEARTH = milestone("hearth");
	public static final ResourceLocation FOUR_RESIDENTS = milestone("four_residents");
	public static final ResourceLocation TWO_BEDS = milestone("two_beds");
	public static final ResourceLocation FOOD_RESERVE = milestone("food_reserve");
	public static final ResourceLocation CULTIVATION = milestone("cultivation");
	public static final ResourceLocation STAPLE_CROP = milestone("staple_crop");
	public static final ResourceLocation BREAD_BAKED = milestone("bread_baked");
	public static final ResourceLocation HOUSING_COMPLETE = milestone("housing_complete");
	public static final ResourceLocation POPULATION_GROWTH = milestone("population_growth");
	public static final ResourceLocation AGRICULTURAL_COMPLETION = milestone("agricultural_completion");
	public static final ResourceLocation METAL_SMELTING = milestone("metal_smelting");
	public static final ResourceLocation METAL_TOOL_PRODUCTION = milestone("metal_tool_production");
	public static final ResourceLocation MECHANICAL_POWER = milestone("mechanical_power");
	public static final ResourceLocation AUTOMATED_PRODUCTION = milestone("automated_production");
	public static final ResourceLocation INFORMATION_STORAGE = milestone("information_storage");
	public static final ResourceLocation COMPUTATION_NETWORK = milestone("computation_network");
	public static final ResourceLocation AUTONOMOUS_PLANNING = milestone("autonomous_planning");
	public static final ResourceLocation ADAPTIVE_LEARNING = milestone("adaptive_learning");
	public static final ResourceLocation SELF_IMPROVING_PRODUCTION = milestone("self_improving_production");
	private static final List<ResourceLocation> AGRICULTURAL_REVIEW = List.of(STAPLE_CROP, BREAD_BAKED, HOUSING_COMPLETE, POPULATION_GROWTH);
	private static final int PROGRESS_INTERVAL_TICKS = 600;
	private static final int AGRICULTURAL_FOOD = 16;
	private static final int CULTIVATED_CROPS = 4;
	private static final List<CivilizationEra> DEFAULT_ERAS = List.of(
		new CivilizationEra(PRIMITIVE, 0, List.of()),
		new CivilizationEra(
			AGRICULTURAL, 10,
			List.of(COMMON_STOCKPILE, HEARTH, FOUR_RESIDENTS, TWO_BEDS, FOOD_RESERVE, CULTIVATION)
		),
		new CivilizationEra(INDUSTRIAL, 20, List.of(AGRICULTURAL_COMPLETION, METAL_SMELTING, METAL_TOOL_PRODUCTION, MECHANICAL_POWER)),
		new CivilizationEra(INFORMATION, 30, List.of(AUTOMATED_PRODUCTION, INFORMATION_STORAGE, COMPUTATION_NETWORK)),
		new CivilizationEra(INTELLIGENT, 40, List.of(AUTONOMOUS_PLANNING, ADAPTIVE_LEARNING, SELF_IMPROVING_PRODUCTION))
	);
	private static List<CivilizationEra> eras = DEFAULT_ERAS;

	private CivilizationEras() {
	}

	public static List<CivilizationEra> all() {
		return eras;
	}

	public static CivilizationEra byId(ResourceLocation id) {
		return eras.stream()
			.filter(era -> era.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown civilization era: " + id));
	}

	public static CivilizationEra byId(String id) {
		return byId(ResourceLocation.parse(id));
	}

	public static Optional<CivilizationEra> next(CivilizationEra era) {
		return eras.stream()
			.filter(candidate -> candidate.order() > era.order())
			.min(Comparator.comparingInt(CivilizationEra::order));
	}

	public static String milestoneTranslationKey(ResourceLocation id) {
		return "coopcraft.milestone." + id.getPath().replace('/', '.');
	}

	public static Optional<ResourceLocation> nextMilestone(SettlementSavedData data, Settlement settlement) {
		Set<ResourceLocation> completed = data.eraMilestonesOf(settlement);
		Optional<CivilizationEra> nextEra = next(data.eraOf(settlement));
		if (nextEra.isPresent() && nextEra.get().id().equals(INDUSTRIAL) && !agriculturalComplete(data, settlement)) {
			Optional<ResourceLocation> review = AGRICULTURAL_REVIEW.stream().filter(milestone -> !completed.contains(milestone)).findFirst();
			if (review.isPresent()) {
				return review;
			}
			return byId(AGRICULTURAL).milestones().stream().filter(milestone -> !completed.contains(milestone)).findFirst();
		}
		return nextEra.flatMap(era -> era.milestones().stream()
			.filter(milestone -> !isMilestoneComplete(data, settlement, milestone)).findFirst());
	}

	public static String nextMilestoneTranslationKey(SettlementSavedData data, Settlement settlement) {
		return nextMilestone(data, settlement).map(CivilizationEras::milestoneTranslationKey).orElse("");
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement, long gameTime) {
		if (Math.floorMod(gameTime + settlement.id().getLeastSignificantBits(), PROGRESS_INTERVAL_TICKS) == 0) {
			refresh(level, data, settlement);
		}
	}

	public static void refresh(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		initializeOrigin(level, data, settlement);
		if (data.eraOf(settlement).id().equals(PRIMITIVE) && data.originOf(settlement) == SettlementOrigin.ESTABLISHED_VILLAGE) {
			observePrimitiveMilestones(level, data, settlement);
			data.setEra(settlement, byId(AGRICULTURAL));
			data.getOrCreateSimulation(settlement).world().recordEraAdvanced(settlement.id().toString(), AGRICULTURAL.toString());
			data.setDirty();
		}
		if (data.eraOf(settlement).id().equals(PRIMITIVE)) {
			observePrimitiveMilestones(level, data, settlement);
		} else if (data.eraOf(settlement).id().equals(AGRICULTURAL)) {
			observeAgriculturalMilestones(level, data, settlement);
		}
		tryAdvance(data, settlement);
	}

	private static void initializeOrigin(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		if (data.hasOrigin(settlement)) {
			return;
		}
		SettlementStages.Survey survey = SettlementStages.survey(
			level, settlement.center(), data.registeredCount(settlement), SettlementStages.SCAN_RADIUS
		);
		data.setOrigin(settlement, isEstablishedVillage(survey) ? SettlementOrigin.ESTABLISHED_VILLAGE : SettlementOrigin.NEW_CAMP);
	}

	private static boolean isEstablishedVillage(SettlementStages.Survey survey) {
		return survey.villageStructure() && (survey.beds() >= 3 || survey.farmland() >= CULTIVATED_CROPS
			|| survey.plantedCrops() >= CULTIVATED_CROPS || survey.smithy());
	}

	static boolean tryAdvance(SettlementSavedData data, Settlement settlement) {
		Optional<CivilizationEra> next = next(data.eraOf(settlement));
		if (next.isEmpty() || !next.get().milestones().stream().allMatch(milestone -> isMilestoneComplete(data, settlement, milestone))) {
			return false;
		}
		data.setEra(settlement, next.get());
		data.getOrCreateSimulation(settlement).world().recordEraAdvanced(settlement.id().toString(), next.get().id().toString());
		data.setDirty();
		return true;
	}

	public static boolean agriculturalComplete(SettlementSavedData data, Settlement settlement) {
		Set<ResourceLocation> completed = data.eraMilestonesOf(settlement);
		return completed.containsAll(AGRICULTURAL_REVIEW) && (data.originOf(settlement) == SettlementOrigin.ESTABLISHED_VILLAGE
			|| completed.containsAll(byId(AGRICULTURAL).milestones()));
	}

	private static boolean isMilestoneComplete(SettlementSavedData data, Settlement settlement, ResourceLocation milestone) {
		return milestone.equals(AGRICULTURAL_COMPLETION)
			? agriculturalComplete(data, settlement)
			: data.eraMilestonesOf(settlement).contains(milestone);
	}

	static boolean isMilestone(ResourceLocation id) {
		return AGRICULTURAL_REVIEW.contains(id)
			|| eras.stream().anyMatch(era -> era.milestones().contains(id));
	}

	private static void observeAgriculturalMilestones(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		if (data.housingCapacity(settlement) > 0) {
			data.completeEraMilestone(settlement, HOUSING_COMPLETE);
		}
		data.simulationOf(settlement).ifPresent(state -> {
			boolean born = state.world().ledger().events().stream().anyMatch(event -> event.type() == com.thysummer.coopcraft.civilization.simulation.EventType.BIRTH);
			if (born) {
				data.completeEraMilestone(settlement, POPULATION_GROWTH);
			}
		});
		if (!data.eraMilestonesOf(settlement).contains(STAPLE_CROP)) {
			SettlementStages.Survey survey = SettlementStages.survey(
				level, settlement.center(), data.registeredCount(settlement), SettlementStages.SCAN_RADIUS
			);
			if (survey.wheatCrops() >= CULTIVATED_CROPS) {
				data.completeEraMilestone(settlement, STAPLE_CROP);
			}
		}
	}

	private static void observePrimitiveMilestones(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		if (SettlementStockpile.getStockpile(level, data, settlement) != null) {
			data.completeEraMilestone(settlement, COMMON_STOCKPILE);
		}
		if (data.getHearth(settlement).isPresent()) {
			data.completeEraMilestone(settlement, HEARTH);
		}
		if (data.registeredCount(settlement) >= 4) {
			data.completeEraMilestone(settlement, FOUR_RESIDENTS);
		}
		if (data.housingCapacity(settlement) >= 2) {
			data.completeEraMilestone(settlement, TWO_BEDS);
		}
		if (SettlementStages.foodUnits(level, data, settlement) >= AGRICULTURAL_FOOD) {
			data.completeEraMilestone(settlement, FOOD_RESERVE);
		}
		Set<ResourceLocation> completed = data.eraMilestonesOf(settlement);
		if (!completed.contains(CULTIVATION)
			&& completed.containsAll(List.of(COMMON_STOCKPILE, HEARTH, FOUR_RESIDENTS, TWO_BEDS, FOOD_RESERVE))) {
			SettlementStages.Survey survey = SettlementStages.survey(
				level, settlement.center(), data.registeredCount(settlement), SettlementStages.SCAN_RADIUS
			);
			if (survey.plantedCrops() >= CULTIVATED_CROPS) {
				data.completeEraMilestone(settlement, CULTIVATION);
			}
		}
	}

	private static ResourceLocation milestone(String path) {
		return CoopCraft.id(path);
	}

	private static List<CivilizationEra> validateOrder(Iterable<CivilizationEra> definitions) {
		List<CivilizationEra> sorted = new ArrayList<>();
		definitions.forEach(sorted::add);
		sorted.sort(Comparator.comparingInt(CivilizationEra::order).thenComparing(era -> era.id().toString()));
		Set<Integer> orders = new HashSet<>();
		for (CivilizationEra era : sorted) {
			if (!orders.add(era.order())) {
				throw new IllegalArgumentException("Civilization era orders must be unique: " + era.order());
			}
		}
		return List.copyOf(sorted);
	}

	public static final class ReloadListener extends SimpleJsonResourceReloadListener implements IdentifiableResourceReloadListener {
		public ReloadListener() {
			super(new Gson(), "civilization/eras");
		}

		@Override
		protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
			Map<ResourceLocation, CivilizationEra> loaded = new LinkedHashMap<>();
			for (CivilizationEra era : DEFAULT_ERAS) {
				loaded.put(era.id(), era);
			}
			resources.forEach((id, json) -> loaded.put(id, CivilizationEra.fromJson(id, json.getAsJsonObject())));
			eras = validateOrder(loaded.values());
		}

		@Override
		public ResourceLocation getFabricId() {
			return CoopCraft.id("civilization_eras");
		}
	}
}
