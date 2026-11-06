package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

public final class VillageOriginGameTest implements FabricGameTest {
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "village_origin_farm")
	public void farmedVillageStartsAgriculturalWithoutSkippingReview(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Farm Village");
		markVanillaVillage(helper);
		for (int index = 0; index < 4; index++) {
			helper.setBlock(new BlockPos(index, -1, 5), Blocks.FARMLAND);
			helper.setBlock(new BlockPos(index, 0, 5), Blocks.POTATOES);
		}

		CivilizationEras.refresh(helper.getLevel(), data, settlement);

		helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, data.originOf(settlement),
			"A real village with cultivated crops must have an agricultural origin");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(),
			"A farmed vanilla village must start agricultural rather than repeat primitive gathering");
		helper.assertValueEqual("coopcraft.milestone.staple_crop", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"The village must still grow the required staple wheat");
		helper.assertFalse(CivilizationEras.agriculturalComplete(data, settlement),
			"Agricultural origin must not fake bread, housing and population growth");
		helper.assertFalse(CivilizationEras.tryAdvance(data, settlement),
			"Agricultural origin alone cannot unlock industry");

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		saved.remove("Origins");
		SettlementSavedData restored = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		Settlement restoredSettlement = restored.find(settlement.center()).orElseThrow();
		CivilizationEras.refresh(helper.getLevel(), restored, restoredSettlement);
		helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, restored.originOf(restoredSettlement),
			"An old save without an origin field must be classified from actual village evidence");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, restored.eraOf(restoredSettlement).id(),
			"Origin migration must preserve the agricultural transition");
		helper.assertValueEqual(26, restored.save(new CompoundTag(), helper.getLevel().registryAccess()).getInt("DataVersion"),
			"Origin state must use the current save version");
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "village_origin_smithy")
	public void smithyVillageStartsAgriculturalButNotIndustrial(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Smithy Village");
		markVanillaVillage(helper);
		helper.setBlock(new BlockPos(1, 0, 4), Blocks.SMITHING_TABLE);
		helper.setBlock(new BlockPos(2, 0, 4), Blocks.FURNACE);

		CivilizationEras.refresh(helper.getLevel(), data, settlement);

		helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, data.originOf(settlement),
			"A real smithing station and furnace must identify an established village");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(),
			"An existing smithy must begin agricultural rather than force a stone-age replay");
		helper.assertFalse(data.eraMilestonesOf(settlement).contains(CivilizationEras.METAL_SMELTING),
			"A workshop alone cannot prove a resident has smelted iron");
		helper.assertFalse(CivilizationEras.tryAdvance(data, settlement),
			"A workshop cannot skip wheat, bread, housing, birth, smelting, tools and power");
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "village_origin_housing")
	public void inhabitedVillageWithoutFieldsStartsAgricultural(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Housed Village");
		markVanillaVillage(helper);
		for (int index = 0; index < 6; index++) {
			helper.setBlock(new BlockPos(index, 0, 0), Blocks.RED_BED);
		}
		helper.setBlock(new BlockPos(5, 0, 4), Blocks.LECTERN);

		CivilizationEras.refresh(helper.getLevel(), data, settlement);

		helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, data.originOf(settlement),
			"Existing housing must identify an inhabited village even when it has no fields or smithy");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(),
			"An inhabited vanilla village must not repeat the primitive era just because it has no farms");
		helper.assertValueEqual("coopcraft.milestone.staple_crop", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"A village without fields must still establish wheat production");
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "village_origin_farmland")
	public void tilledVillageWithoutPlantedCropsStartsAgricultural(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Tilled Village");
		markVanillaVillage(helper);
		for (int index = 0; index < 4; index++) {
			helper.setBlock(new BlockPos(index, -1, 5), Blocks.FARMLAND);
		}

		CivilizationEras.refresh(helper.getLevel(), data, settlement);

		helper.assertValueEqual(SettlementOrigin.ESTABLISHED_VILLAGE, data.originOf(settlement),
			"Existing farmland must count even when its crops have already been harvested");
		helper.assertValueEqual(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id(),
			"Unplanted village farmland must not force a stone-age replay");
		helper.assertFalse(CivilizationEras.agriculturalComplete(data, settlement),
			"Empty farmland must not fake wheat, bread or population growth");
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 600, batch = "village_origin_camp")
	public void emptyStructureAndNewCampKeepPrimitiveOrigin(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Empty Camp");
		markVanillaVillage(helper);

		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertValueEqual(SettlementOrigin.NEW_CAMP, data.originOf(settlement),
			"A structure without housing, fields, crops or a smithy is not an established agricultural village");
		helper.assertValueEqual(CivilizationEras.PRIMITIVE, data.eraOf(settlement).id(),
			"An empty structure must still require primitive accumulation");
		helper.assertValueEqual("coopcraft.milestone.common_stockpile", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"A new camp must first establish shared infrastructure");

		for (int index = 0; index < 4; index++) {
			helper.setBlock(new BlockPos(index, -1, 5), Blocks.FARMLAND);
			helper.setBlock(new BlockPos(index, 0, 5), Blocks.POTATOES);
		}
		CivilizationEras.refresh(helper.getLevel(), data, settlement);
		helper.assertValueEqual(SettlementOrigin.NEW_CAMP, data.originOf(settlement),
			"Later farming must not rewrite the camp's origin or bypass its existing progression");
		helper.assertValueEqual(CivilizationEras.PRIMITIVE, data.eraOf(settlement).id(),
			"A primitive camp must satisfy its actual founding milestones before agriculture");
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		helper.succeed();
	}

	static void markVanillaVillage(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, new BlockPos(2, 0, 2), 8);
		BlockPos center = helper.absolutePos(new BlockPos(2, 0, 2));
		Structure structure = java.util.Objects.requireNonNull(
			helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
				.get(ResourceLocation.fromNamespaceAndPath("minecraft", "village_plains")),
			"Missing vanilla village structure"
		);
		ChunkPos chunkPos = new ChunkPos(center);
		StructureStart start = new StructureStart(structure, chunkPos, 1,
			new PiecesContainer(List.of(new VillagePiece(center))));
		helper.getLevel().structureManager().setStartForStructure(
			SectionPos.of(center), structure, start, helper.getLevel().getChunkAt(center)
		);
		helper.getLevel().structureManager().addReferenceForStructure(
			SectionPos.of(center), structure, chunkPos.toLong(), helper.getLevel().getChunkAt(center)
		);
		helper.assertTrue(helper.getLevel().structureManager().getStructureWithPieceAt(center, StructureTags.VILLAGE).isValid(),
			"The test must contain a real village structure start");
	}

	private static final class VillagePiece extends StructurePiece {
		private final BoundingBox bounds;

		private VillagePiece(BlockPos center) {
			super(StructurePieceType.JIGSAW, villageTag(center));
			bounds = new BoundingBox(center.getX() - 2, center.getY() - 2, center.getZ() - 2,
				center.getX() + 2, center.getY() + 2, center.getZ() + 2);
		}

		private static CompoundTag villageTag(BlockPos center) {
			CompoundTag tag = new CompoundTag();
			tag.put("BB", new IntArrayTag(new int[] {
				center.getX() - 2, center.getY() - 2, center.getZ() - 2,
				center.getX() + 2, center.getY() + 2, center.getZ() + 2
			}));
			return tag;
		}

		@Override
		public BoundingBox getBoundingBox() {
			return bounds;
		}

		@Override
		public StructurePieceType getType() {
			return StructurePieceType.JIGSAW;
		}

		@Override
		protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		}

		@Override
		public void postProcess(
			WorldGenLevel level,
			StructureManager manager,
			ChunkGenerator generator,
			RandomSource random,
			BoundingBox box,
			ChunkPos chunkPos,
			BlockPos pos
		) {
		}
	}
}
