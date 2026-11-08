package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

public record ConstructionPlan(ResourceLocation id, List<ConstructionInstruction> instructions) {
	public ConstructionPlan {
		instructions = List.copyOf(instructions);
	}

	/** Compiles resource JSON without imposing JSON restrictions on synthetic plans. */
	public static ConstructionPlan fromJson(ResourceLocation location, JsonObject json) {
		String context = "definition";
		try {
			if (json.has("id") && !ResourceLocation.parse(readString(json.get("id"), "id")).equals(location)) {
				throw new IllegalArgumentException("id must match resource ID " + location);
			}
			int[] size = json.has("size") ? readTriple(json.get("size"), "size") : new int[] {5, 4, 5};
			for (int axis : size) {
				if (axis < 1 || axis > 64) {
					throw new IllegalArgumentException("size axes must be between 1 and 64");
				}
			}
			Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
			context = "layers";
			JsonArray layers = readArray(json, "layers");
			if (layers.size() > 64) {
				throw new IllegalArgumentException("at most 64 layers are supported");
			}
			for (int i = 0; i < layers.size(); i++) {
				context = "layer " + i;
				JsonObject layer = layers.get(i).getAsJsonObject();
				String type = readString(layer.get("type"), "type");
				if (!type.equals("floor") && !type.equals("perimeter")) {
					throw new IllegalArgumentException("Unknown layer type: " + type);
				}
				int y = readInteger(layer.get("y"), "y");
				context += " at y=" + y;
				if (y < 0 || y >= size[1]) {
					throw new IllegalArgumentException("y is outside size");
				}
				BlockState state = parseBlock(readString(layer.get("block"), "block"));
				for (int x = 0; x < size[0]; x++) {
					for (int z = 0; z < size[2]; z++) {
						boolean perimeter = x == 0 || x == size[0] - 1 || z == 0 || z == size[2] - 1;
						if (type.equals("floor") || perimeter) {
							putBlock(blocks, new BlockPos(x, y, z), state);
						}
					}
				}
			}
			context = "special";
			JsonArray special = readArray(json, "special");
			Set<BlockPos> specialPositions = new HashSet<>();
			for (int i = 0; i < special.size(); i++) {
				context = "special " + i;
				JsonObject block = special.get(i).getAsJsonObject();
				int[] pos = readTriple(block.get("pos"), "pos");
				BlockPos offset = new BlockPos(pos[0], pos[1], pos[2]);
				context += " at offset " + offset;
				for (int axis = 0; axis < 3; axis++) {
					if (pos[axis] < 0 || pos[axis] >= size[axis]) {
						throw new IllegalArgumentException("offset is outside size");
					}
				}
				if (!specialPositions.add(offset)) {
					throw new IllegalArgumentException("Duplicate special offset");
				}
				BlockState state = parseBlock(readString(block.get("block"), "block"));
				blocks.remove(offset);
				putBlock(blocks, offset, state);
			}
			context = "final instructions";
			if (blocks.isEmpty() || blocks.size() > 4096) {
				throw new IllegalArgumentException("plan must contain between 1 and 4096 final instructions");
			}
			List<ConstructionInstruction> instructions = new ArrayList<>();
			for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
				BlockState state = entry.getValue();
				context = "offset " + entry.getKey();
				if (state.isAir() || state.getBlock().asItem() == Items.AIR) {
					throw new IllegalArgumentException("Block has no placeable item: " + state);
				}
				if ((state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && !(state.getBlock() instanceof DoorBlock))
					|| (state.hasProperty(BlockStateProperties.BED_PART) && !(state.getBlock() instanceof BedBlock))) {
					throw new IllegalArgumentException("Unsupported multipart block: " + state);
				}
				instructions.add(new ConstructionInstruction(entry.getKey(), state, state.getBlock().asItem(), consumesItem(state)));
			}
			for (int i = 0; i < instructions.size(); i++) {
				ConstructionInstruction instruction = instructions.get(i);
				context = "offset " + instruction.offset();
				validatePair(instructions, i);
			}
			return new ConstructionPlan(location, instructions);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Blueprint " + location + " (" + context + "): " + exception.getMessage(), exception);
		}
	}

	private static void putBlock(Map<BlockPos, BlockState> blocks, BlockPos offset, BlockState state) {
		blocks.put(offset, state);
		if (blocks.size() > 4096) {
			throw new IllegalArgumentException("plan must contain between 1 and 4096 final instructions; exceeded at offset " + offset);
		}
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

	private static JsonArray readArray(JsonObject json, String name) {
		if (!json.has(name)) {
			return new JsonArray();
		}
		if (!json.get(name).isJsonArray()) {
			throw new IllegalArgumentException(name + " must be an array");
		}
		return json.getAsJsonArray(name);
	}

	private static String readString(JsonElement value, String name) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(name + " must be a string");
		}
		return value.getAsString();
	}

	private static int readInteger(JsonElement value, String name) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
			|| !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
			throw new IllegalArgumentException(name + " must be an integer");
		}
		try {
			return Integer.parseInt(value.getAsString());
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(name + " is outside the integer range", exception);
		}
	}

	private static int[] readTriple(JsonElement value, String name) {
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) {
			throw new IllegalArgumentException(name + " must contain exactly 3 integers");
		}
		JsonArray array = value.getAsJsonArray();
		return new int[] {readInteger(array.get(0), name), readInteger(array.get(1), name), readInteger(array.get(2), name)};
	}

	private static BlockState parseBlock(String text) {
		String id = text;
		Map<String, String> properties = new HashMap<>();
		int bracket = text.indexOf('[');
		if (bracket < 0 && text.indexOf(']') >= 0) {
			throw new IllegalArgumentException("Malformed block property brackets: " + text);
		}
		if (bracket >= 0) {
			if (bracket == 0 || !text.endsWith("]") || text.indexOf('[', bracket + 1) >= 0
				|| text.indexOf(']') != text.length() - 1) {
				throw new IllegalArgumentException("Malformed block property brackets: " + text);
			}
			id = text.substring(0, bracket);
			String values = text.substring(bracket + 1, text.length() - 1);
			for (String entry : values.split(",", -1)) {
				String[] parts = entry.split("=", -1);
				if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
					throw new IllegalArgumentException("Malformed block property assignment: " + entry);
				}
				String name = parts[0].trim();
				if (properties.putIfAbsent(name, parts[1].trim()) != null) {
					throw new IllegalArgumentException("Duplicate block property: " + name);
				}
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

	/** Only validated door/bed second halves are free; other state costs remain one item. */
	private static boolean consumesItem(BlockState state) {
		if (state.getBlock() instanceof DoorBlock) {
			return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
		}
		if (state.getBlock() instanceof BedBlock) {
			return state.getValue(BedBlock.PART) == BedPart.FOOT;
		}
		return true;
	}

	private static void validatePair(List<ConstructionInstruction> instructions, int index) {
		ConstructionInstruction instruction = instructions.get(index);
		BlockState state = instruction.state();
		if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof BedBlock)) {
			return;
		}
		int paidIndex = instruction.consume() ? index : index - 1;
		if (paidIndex < 0 || paidIndex + 1 >= instructions.size()) {
			throw new IllegalArgumentException("Door/bed requires an immediately preceding paid half and following free half");
		}
		ConstructionInstruction paid = instructions.get(paidIndex);
		ConstructionInstruction free = instructions.get(paidIndex + 1);
		if (!paid.consume() || free.consume() || paid.state().getBlock() != free.state().getBlock()) {
			throw new IllegalArgumentException("Door/bed halves must be adjacent in instruction order, paid half first");
		}
		BlockPos expected;
		BlockState expectedState;
		if (paid.state().getBlock() instanceof DoorBlock) {
			expected = paid.offset().above();
			expectedState = paid.state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
		} else {
			expected = paid.offset().relative(paid.state().getValue(BedBlock.FACING));
			expectedState = paid.state().setValue(BedBlock.PART, BedPart.HEAD);
		}
		if (!free.offset().equals(expected) || !free.state().equals(expectedState)) {
			throw new IllegalArgumentException("Mismatched door/bed half: expected " + expectedState + " at offset " + expected);
		}
	}
}
