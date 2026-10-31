package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

public record ConstructionPlan(ResourceLocation id, List<ConstructionInstruction> instructions) {
	public ConstructionPlan {
		instructions = List.copyOf(instructions);
	}

	public static ConstructionPlan fromJson(ResourceLocation location, JsonObject json) {
		int[] size = readSize(json);
		Map<BlockPos, ConstructionInstruction> instructions = new LinkedHashMap<>();
		JsonArray layers = json.has("layers") ? json.getAsJsonArray("layers") : new JsonArray();
		for (JsonElement element : layers) {
			JsonObject layer = element.getAsJsonObject();
			String type = layer.get("type").getAsString();
			int y = layer.get("y").getAsInt();
			BlockState state = parseBlock(layer.get("block").getAsString());
			for (int x = 0; x < size[0]; x++) {
				for (int z = 0; z < size[2]; z++) {
					boolean perimeter = x == 0 || x == size[0] - 1 || z == 0 || z == size[2] - 1;
					if (type.equals("floor") || (type.equals("perimeter") && perimeter)) {
						BlockPos offset = new BlockPos(x, y, z);
						instructions.put(offset, new ConstructionInstruction(offset, state, state.getBlock().asItem(), true));
					}
				}
			}
		}

		JsonArray special = json.has("special") ? json.getAsJsonArray("special") : new JsonArray();
		for (JsonElement element : special) {
			JsonObject block = element.getAsJsonObject();
			BlockPos offset = readPos(block.get("pos").getAsJsonArray());
			BlockState state = parseBlock(block.get("block").getAsString());
			instructions.remove(offset);
			instructions.put(offset, new ConstructionInstruction(offset, state, state.getBlock().asItem(), consumesItem(state)));
		}
		return new ConstructionPlan(
			json.has("id") ? ResourceLocation.parse(json.get("id").getAsString()) : location,
			new ArrayList<>(instructions.values())
		);
	}

	public Map<Item, Integer> materials() {
		Map<Item, Integer> materials = new HashMap<>();
		for (ConstructionInstruction instruction : instructions) {
			if (instruction.consume()) {
				materials.merge(instruction.item(), 1, Integer::sum);
			}
		}
		return materials;
	}

	private static int[] readSize(JsonObject json) {
		if (!json.has("size")) {
			return new int[] {5, 4, 5};
		}
		JsonArray array = json.getAsJsonArray("size");
		return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
	}

	private static BlockPos readPos(JsonArray array) {
		return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
	}

	private static BlockState parseBlock(String text) {
		String id = text;
		Map<String, String> properties = new HashMap<>();
		int bracket = text.indexOf('[');
		if (bracket >= 0) {
			id = text.substring(0, bracket);
			String values = text.substring(bracket + 1, text.length() - 1);
			for (String entry : values.split(",")) {
				String[] parts = entry.split("=", 2);
				properties.put(parts[0].trim(), parts[1].trim());
			}
		}

		ResourceLocation location = ResourceLocation.parse(id);
		if (!BuiltInRegistries.BLOCK.containsKey(location)) {
			throw new IllegalArgumentException("Unknown blueprint block: " + id);
		}
		BlockState state = BuiltInRegistries.BLOCK.get(location).defaultBlockState();
		for (Map.Entry<String, String> entry : properties.entrySet()) {
			state = withProperty(state, entry.getKey(), entry.getValue());
		}
		return state;
	}

	private static BlockState withProperty(BlockState state, String name, String value) {
		Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
		if (property == null) {
			throw new IllegalArgumentException("Unknown blueprint property: " + name);
		}
		return setProperty(state, property, value, name);
	}

	private static <T extends Comparable<T>> BlockState setProperty(
		BlockState state, Property<T> property, String value, String name
	) {
		return state.setValue(property, property.getValue(value).orElseThrow(
			() -> new IllegalArgumentException("Invalid blueprint value " + value + " for " + name)
		));
	}

	private static boolean consumesItem(BlockState state) {
		if (state.hasProperty(DoorBlock.HALF)) {
			return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
		}
		if (state.hasProperty(BedBlock.PART)) {
			return state.getValue(BedBlock.PART) == BedPart.FOOT;
		}
		return true;
	}
}
