package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

import com.thysummer.coopcraft.CoopCraft;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public final class ConstructionPlanner {
	public static final ResourceLocation CAMPFIRE_HEARTH = CoopCraft.id("campfire_hearth");
	public static final ResourceLocation STARTER_WORKSHOP = CoopCraft.id("starter_workshop_v1");
	public static final ResourceLocation STONE_COTTAGE = CoopCraft.id("stone_cottage");
	public static final ResourceLocation TIMBER_PORCH_CABIN = CoopCraft.id("timber_porch_cabin_v1");
	public static final ResourceLocation TIMBER_SIDE_PORCH_CABIN = CoopCraft.id("timber_side_porch_cabin_v1");

	private ConstructionPlanner() {
	}

	public static List<Project> projects(SettlementSavedData data, Settlement settlement) {
		List<Project> projects = new ArrayList<>(3);
		if (data.stageOf(settlement) == SettlementStage.PRIMITIVE && data.getHearth(settlement).isEmpty()) {
			projects.add(new Project(CAMPFIRE_HEARTH));
		}
		HousingPlan housing = housingPlan(data, settlement);
		if (housing.needsHousing()) {
			// Never treat an unknown or unloaded saved station as permission to build a replacement.
			if (data.getCraftingStation(settlement).isEmpty()) projects.add(new Project(STARTER_WORKSHOP));
			projects.add(new Project(housingBlueprint(housing.housingCapacity())));
		}
		return List.copyOf(projects);
	}

	/** Read-only selection for the next project, based on current registered beds, not job history. */
	public static ResourceLocation housingBlueprint(int housingCapacity) {
		if (housingCapacity < 0) throw new IllegalArgumentException("Housing counts cannot be negative");
		return (housingCapacity / 2) % 2 == 0 ? TIMBER_PORCH_CABIN : TIMBER_SIDE_PORCH_CABIN;
	}

	/** Versioned new-house selection never changes a saved job's plan or instruction index. */
	public static boolean isHousingBlueprint(ResourceLocation id) {
		return STONE_COTTAGE.equals(id) || TIMBER_PORCH_CABIN.equals(id) || TIMBER_SIDE_PORCH_CABIN.equals(id);
	}

	/** A paused saved job must not trigger production for a different or unverified plan. */
	public static List<Map<Item, Integer>> materialDemands(SettlementSavedData data, Settlement settlement) {
		ConstructionJob job = data.getConstructionJob(settlement).orElse(null);
		if (job != null) {
			ConstructionPlan plan = BlueprintManager.get(job.blueprintId());
			return "ready".equals(job.compatibilityBlocker(plan))
				? List.of(remainingMaterials(plan, job.nextIndex())) : List.of();
		}
		List<Map<Item, Integer>> demands = new ArrayList<>();
		for (Project project : projects(data, settlement)) {
			ConstructionPlan plan = BlueprintManager.get(project.blueprintId());
			if (plan != null) demands.add(remainingMaterials(plan, 0));
		}
		return List.copyOf(demands);
	}

	/** Consumption still ahead of a saved job, excluding free door/bed continuation parts. */
	public static Map<Item, Integer> remainingMaterials(ConstructionPlan plan, int nextIndex) {
		if (nextIndex < 0 || nextIndex > plan.instructions().size()) {
			throw new IllegalArgumentException("Construction index is outside the plan");
		}
		Map<Item, Integer> materials = new LinkedHashMap<>();
		for (int i = nextIndex; i < plan.instructions().size(); i++) {
			ConstructionInstruction instruction = plan.instructions().get(i);
			if (instruction.consume()) {
				materials.merge(instruction.item(), 1, Integer::sum);
			}
		}
		return Collections.unmodifiableMap(materials);
	}

	public static HousingPlan housingPlan(SettlementSavedData data, Settlement settlement) {
		return new HousingPlan(data.registeredCount(settlement), data.housingCapacity(settlement));
	}

	public record Project(ResourceLocation blueprintId) {
	}

	public record HousingPlan(int registeredMembers, int housingCapacity) {
		public HousingPlan {
			if (registeredMembers < 0 || housingCapacity < 0) {
				throw new IllegalArgumentException("Housing counts cannot be negative");
			}
		}

		public int missingBeds() {
			return Math.max(0, registeredMembers - housingCapacity);
		}

		public boolean needsHousing() {
			return missingBeds() > 0;
		}
	}
}
