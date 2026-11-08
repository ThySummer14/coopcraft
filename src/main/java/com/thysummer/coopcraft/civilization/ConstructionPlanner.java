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
	public static final ResourceLocation STONE_COTTAGE = CoopCraft.id("stone_cottage");

	private ConstructionPlanner() {
	}

	public static List<Project> projects(SettlementSavedData data, Settlement settlement) {
		List<Project> projects = new ArrayList<>(2);
		if (data.stageOf(settlement) == SettlementStage.PRIMITIVE && data.getHearth(settlement).isEmpty()) {
			projects.add(new Project(CAMPFIRE_HEARTH));
		}
		if (housingPlan(data, settlement).needsHousing()) {
			projects.add(new Project(STONE_COTTAGE));
		}
		return List.copyOf(projects);
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
