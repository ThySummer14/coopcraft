package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class SettlementNormGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void refusalLeadsToPersistedRuleThatChangesRealAllocation(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = new SettlementSavedData();
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Norm Settlement", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 3));
		Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 3));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(first, second));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String firstPerson = state.residentId(first.getUUID()).orElseThrow();
		String secondPerson = state.residentId(second.getUUID()).orElseThrow();
		SettlementStockpile.insert(chest, new ItemStack(Items.COD, 20));
		helper.assertValueEqual(20, SettlementSimulationService.importFromStockpile(
			data, settlement, chest, ResourceType.FISH, 20
		), "The test must import twenty real fish");
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setNormsEnabled(data, settlement, true);
		state.world().person(firstPerson).setHunger(100);
		state.world().person(secondPerson).setHunger(100);
		ResourceBatch stock = state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == ResourceType.FISH)
			.findFirst().orElseThrow();

		helper.assertValueEqual(60, SettlementSimulationService.eat(data, settlement, first.getUUID()), "The fixed rule must allow three fish");
		helper.assertValueEqual(0, SettlementSimulationService.eat(data, settlement, first.getUUID()), "The exhausted allowance must reject the second meal");
		helper.assertTrue(state.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.SHARE_REFUSED
			&& event.actors().equals(List.of(firstPerson, state.campOwner().id()))), "The refusal must be a real public-store event");

		advanceDay(helper, data, settlement);
		NormProposal proposal = state.world().proposalsForCamp(state.campOwner().id()).stream().findFirst().orElseThrow();
		helper.assertValueEqual(NormProposal.Status.OPEN, proposal.status(), "The first meeting must leave the proposal open");
		helper.assertValueEqual(1, proposal.rounds(), "The first deliberation must be recorded");
		helper.assertTrue(proposal.knownBy().contains(firstPerson) && proposal.knownBy().contains(secondPerson),
			"Both loaded residents must learn the proposal at the public-store meeting");
		SimulationEvent deliberation = lastEvent(state, EventType.NORM_DELIBERATED);
		helper.assertValueEqual(2, deliberation.actors().size(), "Only the two loaded residents may deliberate");

		SettlementSavedData reloaded = reload(helper, data);
		Settlement reloadedSettlement = reloaded.find(bell).orElseThrow();
		SettlementSimulationState reloadedState = reloaded.simulationOf(reloadedSettlement).orElseThrow();
		NormProposal restoredProposal = reloadedState.world().proposal(proposal.proposalId()).orElseThrow();
		helper.assertValueEqual(1, restoredProposal.rounds(), "Reload must preserve deliberation progress");
		helper.assertValueEqual(proposal.positions(), restoredProposal.positions(), "Reload must preserve participant positions");
		helper.assertTrue(reloadedState.world().person(secondPerson).information().stream()
			.anyMatch(information -> information.objectId().equals(proposal.proposalId())), "Reload must preserve proposal knowledge");

		advanceDay(helper, reloaded, reloadedSettlement);
		Norm norm = reloadedState.world().norm(reloadedState.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE).orElseThrow();
		helper.assertValueEqual(2, norm.allocationUnitCap(), "The adopted rule must choose the hunger-driven cap");
		helper.assertValueEqual(NormProposal.Status.ADOPTED, reloadedState.world().proposal(proposal.proposalId()).orElseThrow().status(),
			"The second deliberation must adopt the supported proposal");
		SimulationEvent adoption = lastEvent(reloadedState, EventType.NORM_ADOPTED);
		helper.assertTrue(reloadedState.world().ledger().traceAncestry(adoption.eventId()).stream()
			.anyMatch(event -> event.type() == EventType.SHARE_REFUSED), "The adopted rule must trace back to the real refusal");
		helper.assertValueEqual(2, reloadedState.remainingFoodUnits(firstPerson), "The new rule must replace the fixed three-fish allowance");
		helper.assertValueEqual(1, reloadedState.world().person(firstPerson).previousSocialHours(), "The prior meeting must reserve one social hour");
		helper.assertValueEqual(120, SettlementSimulationService.gatherWorkDuration(
			reloaded, reloadedSettlement, first.getUUID(), 100
		), "One hour of deliberation must reduce real gathering time");
		helper.assertValueEqual(40, SettlementSimulationService.eat(reloaded, reloadedSettlement, first.getUUID()),
			"The next real meal must consume exactly two fish");
		helper.assertValueEqual(15L, reloadedState.world().batch(stock.batchId()).quantity(), "Seventeen remaining fish minus two must leave fifteen");
		helper.assertValueEqual(EventType.NORM_ENFORCED, lastEvent(reloadedState, EventType.NORM_ENFORCED).type(),
			"Applying the new cap must be observable");
		helper.assertValueEqual(0, chest.countItem(Items.COD), "Abstract meals must never consume the real chest twice");
		reloadedState.world().assertConservation();

		SettlementSavedData finalReload = reload(helper, reloaded);
		Settlement finalSettlement = finalReload.find(bell).orElseThrow();
		SettlementSimulationState finalState = finalReload.simulationOf(finalSettlement).orElseThrow();
		helper.assertValueEqual(2, finalState.world().norm(finalState.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE)
			.orElseThrow().allocationUnitCap(), "The active rule must survive a second reload");
		helper.assertValueEqual(0, finalState.remainingFoodUnits(firstPerson), "Reload must not bypass the new daily cap");
		helper.assertValueEqual(1, finalState.world().norm(finalState.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE)
			.orElseThrow().revisionHistory().size(), "The rule revision history must survive reload");
		finalState.world().assertConservation();
		first.kill();
		second.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void dependentCareRuleChangesRealAllocation(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = new SettlementSavedData();
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Care Settlement", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 3));
		Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 3));
		Villager child = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 4));
		child.setBaby(true);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(first, second));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		helper.assertTrue(state.mapResidentWithParents(child.getUUID(), 80, first.getUUID(), second.getUUID()),
			"The test child must have two mapped parents");
		data.registerMember(settlement, child.getUUID(), 0);
		String firstPerson = state.residentId(first.getUUID()).orElseThrow();
		String secondPerson = state.residentId(second.getUUID()).orElseThrow();
		String childPerson = state.residentId(child.getUUID()).orElseThrow();
		SettlementStockpile.insert(chest, new ItemStack(Items.COD, 20));
		helper.assertValueEqual(20, SettlementSimulationService.importFromStockpile(
			data, settlement, chest, ResourceType.FISH, 20
		), "The test must import twenty real fish");
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setNormsEnabled(data, settlement, true);
		state.world().person(firstPerson).setHunger(100);
		state.world().person(secondPerson).setHunger(100);
		state.world().person(childPerson).setHunger(100);

		helper.assertValueEqual(60, SettlementSimulationService.eat(data, settlement, first.getUUID()),
			"The adult must receive the ordinary three-fish allowance");
		helper.assertValueEqual(0, SettlementSimulationService.eat(data, settlement, first.getUUID()),
			"The adult's next meal must be refused and create a real conflict");
		advanceDay(helper, data, settlement);
		NormProposal proposal = state.world().proposalsForCamp(state.campOwner().id()).stream()
			.filter(candidate -> candidate.normId().equals(Norm.CARE_FIRST_COMMON_STORE)).findFirst().orElseThrow();
		helper.assertValueEqual(1, proposal.allocationUnitCap(), "The care proposal must add one dependent ration");
		helper.assertValueEqual(2, lastEvent(state, EventType.NORM_DELIBERATED).actors().size(),
			"Only the two adults may deliberate the care rule");

		advanceDay(helper, data, settlement);
		Norm care = state.world().norm(state.campOwner().id(), Norm.CARE_FIRST_COMMON_STORE).orElseThrow();
		helper.assertValueEqual(1, care.allocationUnitCap(), "The care rule must be adopted with one bonus ration");
		helper.assertValueEqual(4, state.remainingFoodUnits(childPerson),
			"The child must receive three base rations plus one care ration");
		helper.assertValueEqual(3, state.remainingFoodUnits(firstPerson),
			"The adult must retain only the ordinary base allowance after the day resets");
		helper.assertValueEqual(80, SettlementSimulationService.eat(data, settlement, child.getUUID()),
			"The child must actually consume four fish after the care rule is adopted");
		SimulationEvent enforcement = lastEvent(state, EventType.NORM_ENFORCED);
		helper.assertValueEqual(Norm.CARE_FIRST_COMMON_STORE, enforcement.context().get("normId"),
			"The care rule must have its own enforcement event");
		helper.assertValueEqual("1", enforcement.context().get("careBonus"), "The care bonus must be observable");
		helper.assertValueEqual("4", enforcement.context().get("cap"), "The event must show the combined effective cap");
		helper.assertTrue(state.world().ledger().events().stream().noneMatch(event -> event.type() == EventType.NORM_VIOLATION),
			"A lawful care allocation must not be recorded as a violation");
		helper.assertValueEqual(13L, state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == ResourceType.FISH).mapToLong(batch -> batch.quantity()).sum(),
			"Three adult rations and four child rations must leave thirteen fish");
		state.world().assertConservation();

		SettlementSavedData current = data;
		for (int reload = 0; reload < 2; reload++) {
			current = reload(helper, current);
			Settlement restoredSettlement = current.find(bell).orElseThrow();
			SettlementSimulationState restored = current.simulationOf(restoredSettlement).orElseThrow();
			helper.assertValueEqual(1, restored.world().norm(restored.campOwner().id(), Norm.CARE_FIRST_COMMON_STORE)
				.orElseThrow().allocationUnitCap(), "The care rule must survive reload " + reload);
			helper.assertValueEqual(0, restored.remainingFoodUnits(childPerson),
				"Reload must not reset the child's consumed allowance " + reload);
			helper.assertValueEqual(enforcement, restored.world().ledger().event(enforcement.eventId()).orElseThrow(),
				"The care enforcement event must survive reload " + reload);
			restored.world().assertConservation();
		}
		first.kill();
		second.kill();
		child.kill();
		helper.succeed();
	}

	private static void advanceDay(GameTestHelper helper, SettlementSavedData data, Settlement settlement) {
		helper.getLevel().setDayTime(helper.getLevel().getDayTime() + Level.TICKS_PER_DAY);
		SettlementSimulationService.tick(helper.getLevel(), data, settlement);
	}

	private static SettlementSavedData reload(GameTestHelper helper, SettlementSavedData data) {
		CompoundTag tag = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		return SettlementSavedData.load(tag, helper.getLevel().registryAccess());
	}

	private static SimulationEvent lastEvent(SettlementSimulationState state, EventType type) {
		return state.world().ledger().events().stream().filter(event -> event.type() == type).reduce((first, second) -> second)
			.orElseThrow();
	}
}
