package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("removal")
public final class VillagerInteractionGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void giftKeepsMaterialAndMemoryAcrossReload(GameTestHelper helper) {
		Fixture fixture = fixture(helper);
		fixture.data.setSatiety(fixture.settlement, fixture.villager.getUUID(), 10);
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), fixture.data, fixture.settlement, true);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(fixture.villager.getX(), fixture.villager.getY(), fixture.villager.getZ());
		give(player, new ItemStack(Items.POTATO, 2));

		SettlementSimulationState state = fixture.data.simulationOf(fixture.settlement).orElseThrow();
		long foodBefore = campFoodQuantity(state);
		VillagerInteraction.OfferResult result = VillagerInteraction.offerFood(
			player, fixture.villager, fixture.settlement, fixture.data
		);

		helper.assertTrue(result.accepted(), "A hungry resident must accept the gift: " + result.reason());
		helper.assertValueEqual(1, player.getInventory().countItem(Items.POTATO), "Exactly one potato must leave the player");
		String personId = state.residentId(fixture.villager.getUUID()).orElseThrow();
		helper.assertValueEqual(foodBefore + 1, campFoodQuantity(state), "The potato must enter the single camp inventory");
		helper.assertValueEqual(2, state.world().person(personId).trustIn(playerActor(player)), "Receiving aid must build trust");
		helper.assertValueEqual(EventType.GIFT_RECEIVED, latestEvent(state).type(), "The gift must be recorded in the ledger");
		state.world().assertConservation();

		SettlementSavedData reloaded = reload(helper, fixture.data);
		Settlement reloadedSettlement = reloaded.find(fixture.settlement.center()).orElseThrow();
		SettlementSimulationState reloadedState = reloaded.simulationOf(reloadedSettlement).orElseThrow();
		Person reloadedPerson = reloadedState.world().person(personId);
		helper.assertValueEqual(2, reloadedPerson.trustIn(playerActor(player)), "Trust must survive a reload");
		helper.assertValueEqual(foodBefore + 1, campFoodQuantity(reloadedState), "The gifted stock must survive a reload");
		SimulationEvent event = latestEvent(reloadedState);
		helper.assertValueEqual(EventType.GIFT_RECEIVED, event.type(), "The gift event must survive a reload");
		helper.assertValueEqual(Items.POTATO.getDescriptionId(), event.context().get("item"), "The ledger must keep the offered item");
		helper.assertValueEqual(player.getScoreboardName(), event.context().get("player"), "The ledger must keep the player name");

		fixture.villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void fullResidentDeclinesWithoutConsumingFood(GameTestHelper helper) {
		Fixture fixture = fixture(helper);
		fixture.data.setSatiety(fixture.settlement, fixture.villager.getUUID(), 100);
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), fixture.data, fixture.settlement, true);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(fixture.villager.getX(), fixture.villager.getY(), fixture.villager.getZ());
		give(player, new ItemStack(Items.POTATO, 1));

		SettlementSimulationState state = fixture.data.simulationOf(fixture.settlement).orElseThrow();
		long foodBefore = campFoodQuantity(state);
		VillagerInteraction.OfferResult result = VillagerInteraction.offerFood(
			player, fixture.villager, fixture.settlement, fixture.data
		);

		helper.assertFalse(result.accepted(), "A full resident must decline");
		helper.assertValueEqual("already_full", result.reason(), "The refusal must explain itself");
		helper.assertValueEqual(1, player.getInventory().countItem(Items.POTATO), "Refusal must not consume the item");
		helper.assertValueEqual(foodBefore, campFoodQuantity(state), "Refusal must not create camp stock");
		helper.assertValueEqual(0, state.world().person(state.residentId(fixture.villager.getUUID()).orElseThrow()).trustIn(playerActor(player)), "Refusal must not change trust");
		helper.assertValueEqual(EventType.GIFT_REFUSED, latestEvent(state).type(), "The refusal must be recorded");
		helper.assertValueEqual("already_full", latestEvent(state).context().get("reason"), "The refusal reason must be recorded");
		state.world().assertConservation();

		fixture.villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void giftWithoutSurvivalUsesRealStockpile(GameTestHelper helper) {
		Fixture fixture = fixture(helper);
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), fixture.data, fixture.settlement, false);
		fixture.data.setSatiety(fixture.settlement, fixture.villager.getUUID(), 10);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(fixture.villager.getX(), fixture.villager.getY(), fixture.villager.getZ());
		give(player, new ItemStack(Items.BREAD, 1));

		VillagerInteraction.OfferResult result = VillagerInteraction.offerFood(
			player, fixture.villager, fixture.settlement, fixture.data
		);

		helper.assertTrue(result.accepted(), "A hungry resident must accept real food without survival simulation: " + result.reason());
		helper.assertValueEqual(0, player.getInventory().countItem(Items.BREAD), "The bread must leave the player");
		helper.assertValueEqual(1, chest.countItem(Items.BREAD), "The bread must enter the real stockpile");
		SettlementSimulationState state = fixture.data.simulationOf(fixture.settlement).orElseThrow();
		String personId = state.residentId(fixture.villager.getUUID()).orElseThrow();
		helper.assertValueEqual(2, state.world().person(personId).trustIn(playerActor(player)), "Memory must be recorded even without survival simulation");
		helper.assertValueEqual(EventType.GIFT_RECEIVED, latestEvent(state).type(), "The gift must be recorded in the ledger");

		SettlementSavedData reloaded = reload(helper, fixture.data);
		Settlement reloadedSettlement = reloaded.find(fixture.settlement.center()).orElseThrow();
		Person reloadedPerson = reloaded.simulationOf(reloadedSettlement).orElseThrow().world().person(personId);
		helper.assertValueEqual(2, reloadedPerson.trustIn(playerActor(player)), "Trust must survive a reload in real-stockpile mode");

		fixture.villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void giftIsRefusedWhenPlayerIsTooFar(GameTestHelper helper) {
		Fixture fixture = fixture(helper);
		fixture.data.setSatiety(fixture.settlement, fixture.villager.getUUID(), 10);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(fixture.villager.getX() + 10.0, fixture.villager.getY(), fixture.villager.getZ());
		give(player, new ItemStack(Items.POTATO, 1));

		VillagerInteraction.OfferResult result = VillagerInteraction.offerFood(
			player, fixture.villager, fixture.settlement, fixture.data
		);

		helper.assertFalse(result.accepted(), "A distant offer must be refused");
		helper.assertValueEqual("too_far", result.reason(), "Distance must be explained");
		helper.assertValueEqual(1, player.getInventory().countItem(Items.POTATO), "A rejected offer must not consume the item");

		fixture.villager.kill();
		helper.succeed();
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		player.setItemInHand(InteractionHand.MAIN_HAND, stack);
	}

	private static Fixture fixture(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Interaction Test");
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		return new Fixture(data, settlement, villager);
	}

	private static long campFoodQuantity(SettlementSimulationState state) {
		return state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType().isFood())
			.mapToLong(ResourceBatch::quantity)
			.sum();
	}

	private static SimulationEvent latestEvent(SettlementSimulationState state) {
		return state.world().ledger().events().getLast();
	}

	private static String playerActor(ServerPlayer player) {
		return "minecraft-player:" + player.getUUID();
	}

	private static SettlementSavedData reload(GameTestHelper helper, SettlementSavedData data) {
		CompoundTag tag = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		return SettlementSavedData.load(tag, helper.getLevel().registryAccess());
	}

	private record Fixture(SettlementSavedData data, Settlement settlement, Villager villager) {
	}
}
