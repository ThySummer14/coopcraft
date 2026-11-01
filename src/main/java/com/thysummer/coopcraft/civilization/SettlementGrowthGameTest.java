package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

public final class SettlementGrowthGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void vanillaAdulthoodMapsOnceAndSurvivesReload(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Growth Settlement", 0);
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		villager.setBaby(true);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		helper.assertValueEqual(Person.AgeBand.CHILD, state.world().person(personId).ageBand(), "The real baby must map to a core child");
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setGrowthEnabled(helper.getLevel(), data, settlement, true);
		helper.assertTrue(state.growthEnabled(), "Growth mapping must be explicitly enabled");

		villager.setBaby(false);
		helper.assertValueEqual(Person.AgeBand.CHILD, state.world().person(personId).ageBand(), "The core must wait for its daily observation");
		helper.getLevel().setDayTime(helper.getLevel().getDayTime() + Level.TICKS_PER_DAY);
		SettlementSimulationService.tick(helper.getLevel(), data, settlement);

		helper.assertFalse(villager.isBaby(), "The real villager must remain an adult");
		helper.assertValueEqual(Person.AgeBand.ADULT, state.world().person(personId).ageBand(), "The real adult must map to a core adult");
		helper.assertValueEqual(30L * 360, state.world().person(personId).ageDays(), "The observed stage must use a consistent representative age");
		SimulationEvent transition = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.LIFE_STAGE_CHANGED)
			.findFirst().orElseThrow();
		helper.assertValueEqual(List.of(personId), transition.actors(), "The growing resident must be recorded");
		helper.assertValueEqual("CHILD", transition.context().get("previous"), "The previous stage must be recorded");
		helper.assertValueEqual("ADULT", transition.context().get("current"), "The observed stage must be recorded");
		helper.assertValueEqual("minecraft_villager", transition.context().get("source"), "The real entity must remain the age authority");
		helper.assertValueEqual(villager.getUUID().toString(), transition.context().get("villagerId"), "The real villager must be identifiable");
		int eventCount = state.world().ledger().events().size();
		SettlementSimulationService.tick(helper.getLevel(), data, settlement);
		helper.assertValueEqual(eventCount, state.world().ledger().events().size(), "Same-day observation must not duplicate growth events");

		helper.getLevel().setDayTime(helper.getLevel().getDayTime() + Level.TICKS_PER_DAY);
		SettlementSimulationService.tick(helper.getLevel(), data, settlement);
		helper.assertValueEqual(Person.AgeBand.ADULT, state.world().person(personId).ageBand(), "An ordinary age day must not change the adult stage");
		helper.assertValueEqual(30L * 360 + 1, state.world().person(personId).ageDays(), "The next observed day must advance age exactly once");
		helper.assertValueEqual(eventCount, state.world().ledger().events().size(), "Ordinary age days must not duplicate stage events");

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		SettlementSavedData reloaded = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		SettlementSimulationState restored = reloaded.simulationOf(reloaded.find(bell).orElseThrow()).orElseThrow();
		helper.assertTrue(restored.growthEnabled(), "The growth switch must survive reload");
		helper.assertValueEqual(Person.AgeBand.ADULT, restored.world().person(personId).ageBand(), "The observed adult stage must survive reload");
		helper.assertValueEqual(30L * 360 + 1, restored.world().person(personId).ageDays(), "The exact observed age days must survive reload");
		helper.assertValueEqual(state.world().ledger().events(), restored.world().ledger().events(), "The growth event must survive reload");
		restored.world().assertConservation();

		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, false);
		helper.assertFalse(state.growthEnabled(), "Disabling survival must also disable growth mapping");
		villager.kill();
		helper.succeed();
	}
}
