package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionPlanFingerprintTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:fingerprint_fixture");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void matchesKnownVersionOneCanonicalDigest() {
		// Independently encoded big-endian integers, UTF lengths and boolean byte, then SHA-256.
		var plan = new ConstructionPlan(ID, List.of(stone(new BlockPos(-2, 3, 4))));
		assertEquals("v1-c1c3da11b0b05b8f001421d10bb8e36711c3abaf2e9aebfb1c939789b8b9506d",
			plan.fingerprint());
	}

	@Test
	void equivalentJsonIgnoresWhitespacePropertyOrderAndExplicitDefaults() {
		var implicit = parse("""
			{"special":[{"pos":[0,0,0],"block":"oak_slab"}]}
			""");
		var explicit = parse("""
			{
			  "size": [5, 4, 5],
			  "layers": [],
			  "id": "coopcraft:fingerprint_fixture",
			  "special": [{"block": "minecraft:oak_slab[type=bottom,waterlogged=false]", "pos": [0, 0, 0]}]
			}
			""");
		var reordered = parse("""
			{"special":[{"pos":[0,0,0],"block":"minecraft:oak_slab[ waterlogged=false , type=bottom ]"}],
			 "id":"coopcraft:fingerprint_fixture","layers":[],"size":[5,4,5]}
			""");
		assertEquals(implicit.instructions(), explicit.instructions());
		assertEquals(implicit.instructions(), reordered.instructions());
		assertEquals(implicit.fingerprint(), explicit.fingerprint());
		assertEquals(implicit.fingerprint(), reordered.fingerprint());
	}

	@Test
	void changingOnlyPlanIdChangesFingerprint() {
		var instructions = List.of(stone(BlockPos.ZERO));
		var original = new ConstructionPlan(ID, instructions);
		var renamed = new ConstructionPlan(ResourceLocation.parse("coopcraft:renamed_fixture"), instructions);
		assertEquals(original.instructions(), renamed.instructions());
		assertNotEquals(original.fingerprint(), renamed.fingerprint());
	}

	@Test
	void eachOffsetAxisIndependentlyChangesFingerprint() {
		var original = plan(stone(new BlockPos(-2, 3, 4)));
		for (BlockPos changed : List.of(new BlockPos(-1, 3, 4), new BlockPos(-2, 5, 4), new BlockPos(-2, 3, 6))) {
			assertDifferentSameIdAndLength(original, plan(stone(changed)));
		}
	}

	@Test
	void blockAndItemIdentityIndependentlyChangeFingerprint() {
		var original = plan(stone(BlockPos.ZERO));
		// Synthetic instructions deliberately hold the item fixed when changing the block.
		assertDifferentSameIdAndLength(original, plan(new ConstructionInstruction(
			BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState(), Items.STONE, true)));
		assertDifferentSameIdAndLength(original, plan(new ConstructionInstruction(
			BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Items.COBBLESTONE, true)));
	}

	@Test
	void eachStatePropertyIndependentlyChangesFingerprint() {
		var state = Blocks.OAK_SLAB.defaultBlockState()
			.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
			.setValue(BlockStateProperties.WATERLOGGED, false);
		var original = plan(new ConstructionInstruction(BlockPos.ZERO, state, Items.OAK_SLAB, true));
		assertDifferentSameIdAndLength(original, plan(new ConstructionInstruction(BlockPos.ZERO,
			state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP), Items.OAK_SLAB, true)));
		assertDifferentSameIdAndLength(original, plan(new ConstructionInstruction(BlockPos.ZERO,
			state.setValue(BlockStateProperties.WATERLOGGED, true), Items.OAK_SLAB, true)));
	}

	@Test
	void changingOnlyConsumeChangesFingerprint() {
		assertDifferentSameIdAndLength(plan(stone(BlockPos.ZERO)), plan(new ConstructionInstruction(
			BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Items.STONE, false)));
	}

	@Test
	void instructionOrderAndChangedPrefixOrSuffixInvalidateSameLengthPlan() {
		var first = stone(new BlockPos(0, 0, 0));
		var middle = stone(new BlockPos(1, 0, 0));
		var last = stone(new BlockPos(2, 0, 0));
		var changed = stone(new BlockPos(3, 0, 0));
		var original = plan(first, middle, last);
		assertDifferentSameIdAndLength(original, plan(last, middle, first));
		assertDifferentSameIdAndLength(original, plan(changed, middle, last));
		assertDifferentSameIdAndLength(original, plan(first, changed, last));
		assertDifferentSameIdAndLength(original, plan(first, middle, changed));
		assertNotEquals(original.fingerprint(), plan(first, middle).fingerprint());
	}

	@Test
	void freezesCallerListAndMutableOffsetsBeforeCaching() {
		var mutable = new BlockPos.MutableBlockPos(1, 2, 3);
		var instruction = stone(mutable);
		mutable.set(7, 8, 9);
		assertEquals(new BlockPos(1, 2, 3), instruction.offset(), "Instruction must freeze its offset immediately");
		assertFalse(instruction.offset() instanceof BlockPos.MutableBlockPos);
		var callerList = new ArrayList<>(List.of(instruction));
		var plan = new ConstructionPlan(ID, callerList);
		String fingerprint = plan.fingerprint();
		callerList.set(0, stone(BlockPos.ZERO));
		callerList.add(stone(new BlockPos(4, 5, 6)));
		callerList.clear();
		mutable.set(10, 11, 12);
		assertEquals(List.of(instruction), plan.instructions());
		assertEquals(new BlockPos(1, 2, 3), plan.instructions().getFirst().offset());
		assertSame(fingerprint, plan.fingerprint());
		assertEquals(plan(stone(new BlockPos(1, 2, 3))).fingerprint(), plan.fingerprint());
	}

	@Test
	void instructionsAreUnmodifiableAndFingerprintIsCached() {
		var instruction = stone(BlockPos.ZERO);
		var plan = plan(instruction);
		assertThrows(UnsupportedOperationException.class, () -> plan.instructions().add(instruction));
		assertThrows(UnsupportedOperationException.class, () -> plan.instructions().set(0, stone(new BlockPos(1, 0, 0))));
		assertThrows(UnsupportedOperationException.class, () -> plan.instructions().remove(0));
		String fingerprint = plan.fingerprint();
		for (int i = 0; i < 10; i++) {
			assertSame(fingerprint, plan.fingerprint(), "Return the cached String rather than recomputing the digest");
		}
		assertEquals(List.of(instruction), plan.instructions());
	}

	@Test
	void builtInPlansHaveStableFingerprintsAcrossIndependentParses() throws Exception {
		for (String name : List.of("stone_cottage", "campfire_hearth")) {
			var first = loadBuiltIn(name);
			for (int i = 0; i < 3; i++) {
				var reparsed = loadBuiltIn(name);
				assertNotSame(first, reparsed);
				assertEquals(first.instructions(), reparsed.instructions(), name);
				assertEquals(first.fingerprint(), reparsed.fingerprint(), name);
			}
		}
		assertNotEquals(loadBuiltIn("stone_cottage").fingerprint(), loadBuiltIn("campfire_hearth").fingerprint());
	}

	private static ConstructionPlan loadBuiltIn(String name) throws Exception {
		try (InputStream input = ConstructionPlanFingerprintTest.class.getResourceAsStream(
			"/data/coopcraft/civilization/blueprints/" + name + ".json")) {
			assertNotNull(input, "Missing built-in blueprint: " + name);
			return ConstructionPlan.fromJson(ResourceLocation.parse("coopcraft:" + name),
				JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject());
		}
	}

	private static ConstructionPlan parse(String json) {
		return ConstructionPlan.fromJson(ID, JsonParser.parseString(json).getAsJsonObject());
	}

	private static ConstructionInstruction stone(BlockPos offset) {
		return new ConstructionInstruction(offset, Blocks.STONE.defaultBlockState(), Items.STONE, true);
	}

	private static ConstructionPlan plan(ConstructionInstruction... instructions) {
		return new ConstructionPlan(ID, List.of(instructions));
	}

	private static void assertDifferentSameIdAndLength(ConstructionPlan original, ConstructionPlan changed) {
		assertEquals(original.id(), changed.id());
		assertEquals(original.instructions().size(), changed.instructions().size());
		assertNotEquals(original.fingerprint(), changed.fingerprint());
	}
}
