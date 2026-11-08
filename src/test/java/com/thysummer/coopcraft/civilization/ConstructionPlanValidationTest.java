package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionPlanValidationTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:validation_fixture");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void resourceIdentityAndDefaultDimensionsAreStable() {
		JsonObject json = json("{\"layers\":[{\"type\":\"floor\",\"y\":3,\"block\":\"stone\"}]}");
		ConstructionPlan omitted = parse(json);
		assertEquals(ID, omitted.id());
		assertEquals(25, omitted.instructions().size());
		assertEquals(new BlockPos(4, 3, 4), omitted.instructions().getLast().offset());
		json.addProperty("id", ID.toString());
		assertEquals(omitted, parse(json));
		json.addProperty("id", "coopcraft:other");
		invalid(json, "id");
	}

	@Test
	void dimensionsRequireExactlyThreeBoundedIntegers() {
		for (String size : List.of("null", "{}", "[]", "[1,2]", "[1,2,3,4]", "[0,1,1]", "[-1,1,1]",
			"[1,0,1]", "[1,1,0]", "[65,1,1]", "[1,65,1]", "[1,1,65]", "[1.5,1,1]", "[\"1\",1,1]",
			"[true,1,1]", "[null,1,1]", "[2147483648,1,1]", "[1e100,1,1]")) {
			JsonObject fixture = floor();
			fixture.add("size", JsonParser.parseString(size));
			invalid(fixture, "size");
		}
		assertEquals(1, parse(floor()).instructions().size());
		JsonObject maximum = floor();
		maximum.add("size", JsonParser.parseString("[64,64,64]"));
		assertEquals(4096, parse(maximum).instructions().size());
		ConstructionPlan corner = parse(json("""
			{"size":[64,64,64],"special":[{"pos":[63,63,63],"block":"stone"}]}
			"""));
		assertEquals(new BlockPos(63,63,63), corner.instructions().getFirst().offset());
	}

	@Test
	void layerAndPositionCoordinatesMustBeBoundedIntegers() {
		for (String y : List.of("-1", "1", "0.5", "\"0\"", "true", "null", "4294967296")) {
			JsonObject fixture = floor();
			fixture.getAsJsonArray("layers").get(0).getAsJsonObject().add("y", JsonParser.parseString(y));
			invalid(fixture, "y");
		}
		for (String pos : List.of("[]", "[0,0]", "[0,0,0,0]", "[-1,0,0]", "[5,0,0]",
			"[0,4,0]", "[0,0,5]", "[0,0,0.5]", "[\"0\",0,0]", "[null,0,0]", "[true,0,0]",
			"[4294967296,0,0]")) {
			invalid(json("{\"special\":[{\"pos\":" + pos + ",\"block\":\"stone\"}]}"), "pos", "offset");
		}
	}

	@Test
	void emptyPlansUnknownLayersAndDuplicateSpecialPositionsAreRejected() {
		invalid(json("{}"), "final instructions");
		JsonObject unknown = floor();
		unknown.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("type", "ceiling");
		invalid(unknown, "type");
		invalid(specials("stone", "dirt", "[0,0,0]", "[0,0,0]"), "duplicate");
	}

	@Test
	void capsLayersAndFinalInstructionsButAllowsOverlappingLayers() {
		JsonObject fixture = floor();
		JsonArray layers = fixture.getAsJsonArray("layers");
		JsonObject layer = layers.get(0).getAsJsonObject().deepCopy();
		for (int i = 1; i < 64; i++) layers.add(layer.deepCopy());
		assertEquals(1, parse(fixture).instructions().size());
		layers.add(layer.deepCopy());
		invalid(fixture, "layer");

		JsonObject tooLarge = floor();
		tooLarge.add("size", JsonParser.parseString("[64,2,64]"));
		JsonObject upper = layer.deepCopy();
		upper.addProperty("y", 1);
		tooLarge.getAsJsonArray("layers").add(upper);
		invalid(tooLarge, "4096");
	}

	@Test
	void layerOverridesKeepOrderAndSpecialOverridesMoveToEnd() {
		ConstructionPlan plan = parse(json("""
			{"size":[2,1,2],"layers":[
			 {"type":"floor","y":0,"block":"stone"},
			 {"type":"perimeter","y":0,"block":"dirt"}],
			 "special":[{"pos":[0,0,0],"block":"cobblestone"}]}
			"""));
		assertEquals(List.of(new BlockPos(0,0,1), new BlockPos(1,0,0), new BlockPos(1,0,1), new BlockPos(0,0,0)),
			plan.instructions().stream().map(ConstructionInstruction::offset).toList());
		assertEquals(Map.of(Items.DIRT, 3, Items.COBBLESTONE, 1), plan.materials());
	}

	@Test
	void doorPairsInLayersAndSpecialsAndBedPairsChargeOnce() {
		ConstructionPlan layers = parse(json("""
			{"size":[1,2,1],"layers":[
			 {"type":"floor","y":0,"block":"oak_door[half=lower]"},
			 {"type":"floor","y":1,"block":"oak_door[half=upper]"}]}
			"""));
		assertPair(layers, Items.OAK_DOOR);
		assertPair(parse(door()), Items.OAK_DOOR);
		assertPair(parse(bed()), Items.WHITE_BED);
	}

	@Test
	void incompleteOrOverwrittenMultipartObjectsAreRejected() {
		for (JsonObject pair : List.of(door(), bed())) {
			for (int removed : List.of(0, 1)) {
				JsonObject fixture = pair.deepCopy();
				fixture.getAsJsonArray("special").remove(removed);
				invalid(fixture, "half", "halves");
			}
		}
		invalid(json("""
			{"size":[1,2,1],"layers":[
			 {"type":"floor","y":0,"block":"oak_door[half=lower]"},
			 {"type":"floor","y":1,"block":"oak_door[half=upper]"}],
			 "special":[{"pos":[0,1,0],"block":"stone"}]}
			"""), "half", "halves");
	}

	@Test
	void multipartTypeColorFacingAndOtherStateMustMatch() {
		for (String upper : List.of("iron_door[half=upper]", "oak_door[half=upper,facing=south]",
			"oak_door[half=upper,hinge=right]", "oak_door[half=upper,open=true]",
			"oak_door[half=upper,powered=true]", "stone")) {
			JsonObject fixture = door();
			fixture.getAsJsonArray("special").get(1).getAsJsonObject().addProperty("block", upper);
			invalid(fixture, "half", "halves");
		}
		for (String head : List.of("red_bed[facing=south,part=head]", "white_bed[facing=north,part=head]",
			"white_bed[facing=south,part=head,occupied=true]")) {
			JsonObject fixture = bed();
			fixture.getAsJsonArray("special").get(1).getAsJsonObject().addProperty("block", head);
			invalid(fixture, "half", "halves");
		}
	}

	@Test
	void multipartSpatialAdjacencyAndInstructionAdjacencyAreBothRequired() {
		for (JsonObject pair : List.of(door(), bed())) {
			JsonObject displaced = pair.deepCopy();
			displaced.getAsJsonArray("special").get(1).getAsJsonObject().add("pos", JsonParser.parseString("[2,2,2]"));
			invalid(displaced, "half", "halves");
			JsonObject reversed = pair.deepCopy();
			JsonArray reversedParts = reversed.getAsJsonArray("special");
			reversedParts.add(reversedParts.remove(0));
			invalid(reversed, "half", "halves");
			JsonObject separated = pair.deepCopy();
			JsonArray parts = separated.getAsJsonArray("special");
			var last = parts.remove(1);
			parts.add(json("{\"pos\":[3,0,3],\"block\":\"stone\"}"));
			parts.add(last);
			invalid(separated, "half", "halves");
		}
	}

	@Test
	void otherDoubleHeightBlocksCannotReceiveFreeContinuationParts() {
		invalid(specials("tall_grass[half=lower]", "tall_grass[half=upper]", "[0,0,0]", "[0,1,0]"), "support");
	}

	@Test
	void airAndBlocksWithoutPlaceableItemsAreRejected() {
		for (String block : List.of("air", "cave_air", "void_air", "water", "water[level=1]", "lava")) {
			JsonObject fixture = floor();
			fixture.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("block", block);
			assertTrue(invalid(fixture, "no placeable item").getMessage().contains("offset"));
		}
		assertEquals(Map.of(Items.STONE, 1), parse(floor()).materials());
	}

	@Test
	void malformedBlockSyntaxUnknownStatesAndDuplicatePropertiesAreRejected() {
		for (String block : List.of("oak_door[half=lower", "stone]", "stone[]trailing", "stone[[x=y]]",
			"oak_door[half=lower,half=upper]", "stone[missing=true]", "oak_door[half=sideways]",
			"oak_door[half]", "oak_door[half=lower,]", "coopcraft:does_not_exist")) {
			JsonObject fixture = floor();
			fixture.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("block", block);
			invalid(fixture, "block", "property", "value", "syntax", "bracket");
		}
	}

	@Test
	void shippedPlansConserveEverySuffixWithoutMutatingInstructionsOrJson() throws Exception {
		for (String name : List.of("stone_cottage", "campfire_hearth")) {
			try (InputStream input = getClass().getResourceAsStream("/data/coopcraft/civilization/blueprints/" + name + ".json")) {
				assertNotNull(input);
				JsonObject source = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				String originalJson = source.toString();
				ConstructionPlan plan = ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name), source);
				List<ConstructionInstruction> original = new ArrayList<>(plan.instructions());
				Map<Item, Integer> paid = new HashMap<>();
				for (int index = 0; index <= original.size(); index++) {
					Map<Item, Integer> remaining = ConstructionPlanner.remainingMaterials(plan, index);
					assertEquals(remaining, ConstructionPlanner.remainingMaterials(plan, index));
					Map<Item, Integer> reconstructed = new HashMap<>(paid);
					remaining.forEach((item, count) -> reconstructed.merge(item, count, Integer::sum));
					assertEquals(plan.materials(), reconstructed, name + " at index " + index);
					if (index < original.size() && original.get(index).consume()) paid.merge(original.get(index).item(), 1, Integer::sum);
				}
				plan.materials().clear();
				assertEquals(paid, plan.materials());
				assertEquals(original, plan.instructions());
				assertEquals(originalJson, source.toString());
			}
		}
	}

	private static void assertPair(ConstructionPlan plan, Item item) {
		assertEquals(Map.of(item, 1), plan.materials());
		assertEquals(List.of(true, false), plan.instructions().stream().map(ConstructionInstruction::consume).toList());
		assertEquals(Map.of(), ConstructionPlanner.remainingMaterials(plan, 1));
	}

	private static JsonObject door() {
		return specials("oak_door[facing=north,half=lower]", "oak_door[facing=north,half=upper]", "[0,0,0]", "[0,1,0]");
	}

	private static JsonObject bed() {
		return specials("white_bed[facing=south,part=foot]", "white_bed[facing=south,part=head]", "[0,0,0]", "[0,0,1]");
	}

	private static JsonObject specials(String first, String second, String firstPos, String secondPos) {
		return json("{\"special\":[{\"pos\":" + firstPos + ",\"block\":\"" + first + "\"},"
			+ "{\"pos\":" + secondPos + ",\"block\":\"" + second + "\"}]}");
	}

	private static JsonObject floor() {
		return json("{\"size\":[1,1,1],\"layers\":[{\"type\":\"floor\",\"y\":0,\"block\":\"stone\"}]}");
	}

	private static JsonObject json(String source) {
		return JsonParser.parseString(source).getAsJsonObject();
	}

	private static ConstructionPlan parse(JsonObject source) {
		return ConstructionPlan.fromJson(ID, source);
	}

	private static IllegalArgumentException invalid(JsonObject source, String... reasons) {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(source), source::toString);
		String message = error.getMessage().toLowerCase(Locale.ROOT);
		assertTrue(message.contains(ID.toString()), error::getMessage);
		assertTrue(java.util.Arrays.stream(reasons).anyMatch(message::contains), error::getMessage);
		return error;
	}
}
