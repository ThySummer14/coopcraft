package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.Villager;

public final class SettlementKinshipGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void realBirthRecordsParentsHouseholdAndReloads(GameTestHelper helper) {
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Kinship Settlement", 0);
		Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 3));
		Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 3));
		first.setCustomName(Component.literal("Ada"));
		second.setCustomName(Component.literal("Bram"));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(first, second));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String firstPerson = state.residentId(first.getUUID()).orElseThrow();
		String secondPerson = state.residentId(second.getUUID()).orElseThrow();
		String firstHousehold = state.world().person(firstPerson).householdId();

		Villager child = breed(helper, first, second);
		child.setCustomName(Component.literal("Newborn"));

		helper.assertTrue(data.isMember(settlement, child.getUUID()), "The real newborn must join its parents' settlement");
		String childPerson = state.residentId(child.getUUID()).orElseThrow();
		Person mappedChild = state.world().person(childPerson);
		helper.assertValueEqual(List.of(firstPerson, secondPerson), mappedChild.parents(),
			"The original breeding pair must be recorded in order");
		helper.assertValueEqual(firstHousehold, mappedChild.householdId(), "The child must join the first parent's household");
		helper.assertValueEqual(2, state.world().peopleInHousehold(firstHousehold).size(),
			"Existing household behavior must include the real child");
		helper.assertValueEqual(1, state.world().peopleInHousehold(state.world().person(secondPerson).householdId()).size(),
			"The second parent's existing household must not be merged implicitly");
		SimulationEvent birth = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.BIRTH)
			.filter(event -> event.actors().equals(List.of(firstPerson, secondPerson, childPerson)))
			.findFirst().orElseThrow();
		helper.assertValueEqual("minecraft_villager", birth.context().get("source"), "The birth must come from the real entity");
		helper.assertValueEqual(child.getUUID().toString(), birth.context().get("villagerId"), "The birth must identify the real child");
		SettlementOverviewPayload.SimulationEventInfo info = Civilization.toSimulationEventInfo(
			helper.getLevel(), data, settlement, birth
		);
		helper.assertValueEqual(List.of(
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.parents", "Ada, Bram"),
			new SettlementOverviewPayload.SimulationEventDetail("coopcraft.simulation.detail.child", "Newborn")
		), info.details(), "The event interface must identify the parents and child");
		int eventCount = state.world().ledger().events().size();
		helper.assertFalse(SettlementSimulationService.recordBirth(helper.getLevel(), first, second, child),
			"Registering the same real newborn twice must be rejected");
		helper.assertValueEqual(eventCount, state.world().ledger().events().size(),
			"A duplicate callback must not duplicate the birth event");
		state.world().assertConservation();

		SettlementSavedData current = data;
		for (int reload = 0; reload < 2; reload++) {
			current = SettlementSavedData.load(
				current.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
			);
			Settlement restoredSettlement = current.find(bell).orElseThrow();
			SettlementSimulationState restored = current.simulationOf(restoredSettlement).orElseThrow();
			Person restoredChild = restored.world().person(childPerson);
			helper.assertTrue(current.isMember(restoredSettlement, child.getUUID()),
				"The newborn's settlement membership must survive reload " + reload);
			helper.assertValueEqual(List.of(firstPerson, secondPerson), restoredChild.parents(),
				"Both parents must survive reload " + reload);
			helper.assertValueEqual(firstHousehold, restoredChild.householdId(),
				"The child's household must survive reload " + reload);
			helper.assertValueEqual(2, restored.world().peopleInHousehold(firstHousehold).size(),
				"Household behavior must retain the child after reload " + reload);
			helper.assertValueEqual(birth, restored.world().ledger().event(birth.eventId()).orElseThrow(),
				"The real birth event must survive reload " + reload);
			restored.world().assertConservation();
		}
		first.kill();
		second.kill();
		child.kill();
		helper.succeed();
	}

	private static Villager breed(GameTestHelper helper, Villager first, Villager second) {
		try {
			Method breed = VillagerMakeLove.class.getDeclaredMethod("breed", ServerLevel.class, Villager.class, Villager.class);
			breed.setAccessible(true);
			Object result = breed.invoke(new VillagerMakeLove(), helper.getLevel(), first, second);
			return (Villager) ((Optional<?>) result).orElseThrow();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not invoke vanilla villager breeding", exception);
		}
	}
}
