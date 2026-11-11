package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivilizationErasTest {
	@Test
	void builtInErasFollowTheFiveAgeSequence() {
		assertEquals(List.of(
			CivilizationEras.PRIMITIVE,
			CivilizationEras.AGRICULTURAL,
			CivilizationEras.INDUSTRIAL,
			CivilizationEras.INFORMATION,
			CivilizationEras.INTELLIGENT
		), CivilizationEras.all().stream().map(CivilizationEra::id).toList());
	}

	@Test
	void agriculturalAdvancementRequiresEveryMilestone() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		for (ResourceLocation milestone : CivilizationEras.byId(CivilizationEras.AGRICULTURAL).milestones()) {
			if (!milestone.equals(CivilizationEras.CULTIVATION)) {
				data.completeEraMilestone(settlement, milestone);
			}
		}

		assertFalse(CivilizationEras.tryAdvance(data, settlement));
		assertEquals(CivilizationEras.PRIMITIVE, data.eraOf(settlement).id());
		assertEquals("coopcraft.milestone.cultivation", CivilizationEras.nextMilestoneTranslationKey(data, settlement));

		data.completeEraMilestone(settlement, CivilizationEras.CULTIVATION);

		assertTrue(CivilizationEras.tryAdvance(data, settlement));
		assertEquals(CivilizationEras.AGRICULTURAL, data.eraOf(settlement).id());
		assertEquals("coopcraft.milestone.staple_crop", CivilizationEras.nextMilestoneTranslationKey(data, settlement));
	}

	@Test
	void agriculturalReviewBlocksIndustrialUntilCompleted() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		data.setEra(settlement, CivilizationEras.byId(CivilizationEras.AGRICULTURAL));
		for (ResourceLocation milestone : CivilizationEras.byId(CivilizationEras.AGRICULTURAL).milestones()) {
			data.completeEraMilestone(settlement, milestone);
		}

		assertFalse(CivilizationEras.tryAdvance(data, settlement));
		assertEquals("coopcraft.milestone.staple_crop", CivilizationEras.nextMilestoneTranslationKey(data, settlement));
		for (ResourceLocation milestone : List.of(CivilizationEras.STAPLE_CROP, CivilizationEras.BREAD_BAKED, CivilizationEras.HOUSING_COMPLETE)) {
			data.completeEraMilestone(settlement, milestone);
		}
		assertFalse(CivilizationEras.agriculturalComplete(data, settlement));
		assertEquals("coopcraft.milestone.population_growth", CivilizationEras.nextMilestoneTranslationKey(data, settlement));

		data.completeEraMilestone(settlement, CivilizationEras.POPULATION_GROWTH);
		assertTrue(CivilizationEras.agriculturalComplete(data, settlement));
		assertEquals("coopcraft.milestone.metal_smelting", CivilizationEras.nextMilestoneTranslationKey(data, settlement));
		assertFalse(CivilizationEras.tryAdvance(data, settlement));

		for (ResourceLocation milestone : List.of(CivilizationEras.METAL_SMELTING, CivilizationEras.METAL_TOOL_PRODUCTION, CivilizationEras.MECHANICAL_POWER)) {
			data.completeEraMilestone(settlement, milestone);
		}
		assertTrue(CivilizationEras.tryAdvance(data, settlement));
		assertEquals(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id());
	}

	@Test
	void establishedVillageReviewSkipsOnlyPrimitiveRequirements() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Established Village", 0);
		data.setEra(settlement, CivilizationEras.byId(CivilizationEras.AGRICULTURAL));
		for (ResourceLocation milestone : List.of(
			CivilizationEras.STAPLE_CROP,
			CivilizationEras.BREAD_BAKED,
			CivilizationEras.HOUSING_COMPLETE,
			CivilizationEras.POPULATION_GROWTH
		)) {
			data.completeEraMilestone(settlement, milestone);
		}

		assertFalse(CivilizationEras.agriculturalComplete(data, settlement));
		assertEquals("coopcraft.milestone.common_stockpile", CivilizationEras.nextMilestoneTranslationKey(data, settlement),
			"A new camp must expose every missing primitive prerequisite rather than hide it");
		data.setOrigin(settlement, SettlementOrigin.ESTABLISHED_VILLAGE);
		assertTrue(CivilizationEras.agriculturalComplete(data, settlement),
			"An established village must not repeat primitive accumulation after completing the agricultural review");
		assertFalse(data.eraMilestonesOf(settlement).contains(CivilizationEras.COMMON_STOCKPILE));
		assertEquals("coopcraft.milestone.metal_smelting", CivilizationEras.nextMilestoneTranslationKey(data, settlement));
		assertFalse(CivilizationEras.tryAdvance(data, settlement), "Agricultural completion alone cannot unlock industry");

		for (ResourceLocation milestone : List.of(
			CivilizationEras.METAL_SMELTING, CivilizationEras.METAL_TOOL_PRODUCTION, CivilizationEras.MECHANICAL_POWER
		)) {
			data.completeEraMilestone(settlement, milestone);
		}
		assertTrue(CivilizationEras.tryAdvance(data, settlement));
		assertEquals(CivilizationEras.INDUSTRIAL, data.eraOf(settlement).id());
		assertEquals("coopcraft.milestone.automated_production", CivilizationEras.nextMilestoneTranslationKey(data, settlement));
	}

	@Test
	void laterEraMilestonesCannotSkipAnEra() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		for (ResourceLocation milestone : CivilizationEras.byId(CivilizationEras.INDUSTRIAL).milestones()) {
			data.completeEraMilestone(settlement, milestone);
		}

		assertFalse(CivilizationEras.tryAdvance(data, settlement));
		assertEquals(CivilizationEras.PRIMITIVE, data.eraOf(settlement).id());
	}

	@Test
	void eraDefinitionsCanComeFromJson() {
		JsonObject json = new JsonObject();
		json.addProperty("order", 15);
		json.add("milestones", com.google.gson.JsonParser.parseString("[\"coopcraft:powered_workshop\"]"));
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("example", "early_industry");

		CivilizationEra era = CivilizationEra.fromJson(id, json);

		assertEquals(id, era.id());
		assertEquals(15, era.order());
		assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("coopcraft", "powered_workshop")), era.milestones());
		assertEquals("coopcraft.era.early_industry", era.translationKey());
	}
}
