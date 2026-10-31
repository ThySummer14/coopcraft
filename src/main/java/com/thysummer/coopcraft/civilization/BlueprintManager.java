package com.thysummer.coopcraft.civilization;

import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.thysummer.coopcraft.CoopCraft;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

public final class BlueprintManager extends SimpleJsonResourceReloadListener implements IdentifiableResourceReloadListener {
	private static Map<ResourceLocation, ConstructionPlan> plans = Map.of();

	public BlueprintManager() {
		super(new Gson(), "civilization/blueprints");
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
		plans = resources.entrySet().stream().collect(
			java.util.stream.Collectors.toUnmodifiableMap(
				Map.Entry::getKey,
				entry -> ConstructionPlan.fromJson(entry.getKey(), entry.getValue().getAsJsonObject())
			)
		);
	}

	public static ConstructionPlan get(ResourceLocation id) {
		return plans.get(id);
	}

	@Override
	public ResourceLocation getFabricId() {
		return CoopCraft.id("blueprints");
	}
}
