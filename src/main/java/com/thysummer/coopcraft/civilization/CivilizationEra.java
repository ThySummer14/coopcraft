package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

public record CivilizationEra(ResourceLocation id, int order, List<ResourceLocation> milestones) {
	public CivilizationEra {
		Objects.requireNonNull(id, "id");
		if (order < 0) {
			throw new IllegalArgumentException("Era order cannot be negative");
		}
		milestones = List.copyOf(milestones);
		if (new HashSet<>(milestones).size() != milestones.size()) {
			throw new IllegalArgumentException("Era milestones must be unique");
		}
	}

	public String translationKey() {
		return translationKey(id);
	}

	public static String translationKey(ResourceLocation id) {
		return "coopcraft.era." + id.getPath().replace('/', '.');
	}

	static CivilizationEra fromJson(ResourceLocation id, JsonObject json) {
		JsonArray milestoneArray = json.getAsJsonArray("milestones");
		List<ResourceLocation> milestones = new ArrayList<>();
		if (milestoneArray != null) {
			for (JsonElement milestone : milestoneArray) {
				milestones.add(ResourceLocation.parse(milestone.getAsString()));
			}
		}
		return new CivilizationEra(id, json.get("order").getAsInt(), milestones);
	}
}
