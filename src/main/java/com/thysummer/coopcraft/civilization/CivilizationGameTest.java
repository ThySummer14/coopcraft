package com.thysummer.coopcraft.civilization;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CivilizationGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos NEARBY_BELL = new BlockPos(4, 0, 2);
	private static final BlockPos CHEST = new BlockPos(5, 0, 2);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(6, 0, 2);
	private static final BlockPos LOG = new BlockPos(1, 0, 3);
	private static final BlockPos STONE_A = new BlockPos(2, 0, 3);
	private static final BlockPos STONE_B = new BlockPos(3, 0, 3);
	private static final BlockPos STONE_C = new BlockPos(4, 0, 3);
	private static final BlockPos STONE_D = new BlockPos(5, 0, 3);
	private static final BlockPos VILLAGER = new BlockPos(3, 0, 2);
	private static final BlockPos GRINDING_STATION = new BlockPos(1, 0, 5);

	@SuppressWarnings("removal")
	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60000)
	public void foundsAndReusesSettlement(GameTestHelper helper) {
		helper.setBlock(BELL, Blocks.BELL);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);

		useBlock(helper, player, BELL);
		helper.assertFalse(data.find(bell).isPresent(), "A settlement cannot be founded without villagers");

		Villager first = helper.spawn(EntityType.VILLAGER, VILLAGER);
		useBlock(helper, player, BELL);
		helper.assertFalse(data.find(bell).isPresent(), "One villager is not enough to found a settlement");

		Villager second = helper.spawn(EntityType.VILLAGER, VILLAGER);
		player.setShiftKeyDown(true);
		useBlock(helper, player, BELL);
		helper.assertFalse(data.find(bell).isPresent(), "Sneaking must preserve the vanilla interaction");

		player.setShiftKeyDown(false);
		useBlock(helper, player, BELL);
		Settlement settlement = data.find(bell).orElseThrow();
		helper.assertValueEqual(bell, settlement.center(), "The settlement must be centered on the bell");
		helper.assertValueEqual(
			SocialOrganization.PRIMITIVE_COMMUNE,
			data.socialOrganizationOf(settlement),
			"A new settlement must begin as a primitive commune"
		);
		helper.assertFalse(settlement.name().isBlank(), "The settlement must receive a name");
		helper.assertValueEqual(
			helper.getLevel().getDayTime() / Level.TICKS_PER_DAY,
			settlement.foundedDay(),
			"The founding day must match the world day"
		);

		helper.setBlock(NEARBY_BELL, Blocks.BELL);
		useBlock(helper, player, NEARBY_BELL);
		Settlement nearby = data.find(helper.absolutePos(NEARBY_BELL)).orElseThrow();
		helper.assertValueEqual(settlement, nearby, "Nearby bells must reuse the same settlement");
		helper.assertTrue(data.isMember(settlement, first.getUUID()), "The first villager must become a member");
		helper.assertTrue(data.isMember(settlement, second.getUUID()), "The second villager must become a member");
		helper.assertValueEqual(2, data.registeredCount(settlement), "Both villagers must be registered");
		var selectedLeader = data.leaderOf(settlement).orElseThrow();
		helper.assertTrue(selectedLeader.equals(first.getUUID()) || selectedLeader.equals(second.getUUID()), "A founder must become chief");
		int leaderScore = data.personalityOf(settlement, selectedLeader).leadershipScore();
		int otherScore = data.personalityOf(settlement, selectedLeader.equals(first.getUUID()) ? second.getUUID() : first.getUUID()).leadershipScore();
		helper.assertTrue(leaderScore >= otherScore, "The adult with the highest leadership score must coordinate the commune");

		helper.setBlock(CHEST, Blocks.CHEST);
		player.setShiftKeyDown(true);
		useBlock(helper, player, CHEST);
		player.setShiftKeyDown(false);
		helper.assertValueEqual(
			helper.absolutePos(CHEST),
			data.getStockpile(settlement).orElseThrow(),
			"The chest must become the settlement stockpile"
		);
		first.getInventory().setItem(0, new ItemStack(Items.BREAD, 16));
		second.kill();
		data.registerLoadedResidents(helper.getLevel(), settlement, java.util.List.of(first));
		helper.assertValueEqual(first.getUUID(), data.leaderOf(settlement).orElseThrow(), "A dead chief must be replaced by the remaining adult");

		helper.runAfterDelay(100, () -> {
			Container stockpile = (Container) helper.getBlockEntity(CHEST);
			runStockpileCycles(helper, data, settlement);
			int retainedFoodPoints = data.personalityOf(settlement, first.getUUID()).foodRetentionPoints(12);
			int basicFoodPoints = data.personalityOf(settlement, first.getUUID()).basicFoodPoints(12);
			int expectedDeposit = (64 - retainedFoodPoints) / 4;
			int expectedRations = (basicFoodPoints - 4 + 3) / 4;
			helper.assertValueEqual(expectedDeposit, stockpile.countItem(Items.BREAD), "Food deposits must respect the villager's selfishness");
			helper.assertValueEqual(16 - expectedDeposit, first.getInventory().getItem(0).getCount(), "The villager must retain food according to personality");
			helper.assertFalse(data.isMember(settlement, second.getUUID()), "Dead villagers must lose membership");
			helper.assertValueEqual(1, data.registeredCount(settlement), "Only the living villager remains registered");
			helper.assertTrue(data.getConstructionJob(settlement).isEmpty(), "Construction cannot start without all materials");
			helper.assertValueEqual(0, data.housingCapacity(settlement), "No materials means no housing");

			first.getInventory().setItem(0, new ItemStack(Items.BREAD));
			runStockpileCycles(helper, data, settlement);
			helper.runAfterDelay(80, () -> {
				helper.assertValueEqual(1 + expectedRations, first.getInventory().countItem(Items.BREAD), "The commune must supply individual basic rations");
				helper.assertValueEqual(expectedDeposit - expectedRations, stockpile.countItem(Items.BREAD), "Rations must come from the real stockpile");
				helper.setBlock(LOG, Blocks.OAK_LOG);
				helper.setBlock(STONE_A, Blocks.STONE);
				helper.setBlock(STONE_B, Blocks.STONE);
				helper.setBlock(STONE_C, Blocks.STONE);
				player.setShiftKeyDown(true);
				useBlock(helper, player, LOG);
				useBlock(helper, player, STONE_A);
				useBlock(helper, player, STONE_B);
				useBlock(helper, player, STONE_C);
				player.setShiftKeyDown(false);
				helper.assertTrue(data.resourcesOf(settlement).contains(helper.absolutePos(LOG)), "The log must be marked");
				helper.assertTrue(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_A)), "The first stone must be marked");
				helper.assertTrue(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_B)), "The second stone must be marked");
				helper.assertTrue(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_C)), "The third stone must be marked");

				helper.runAfterDelay(500, () -> {
					runStockpileCycles(helper, data, settlement);
					helper.assertFalse(helper.getBlockState(LOG).is(Blocks.OAK_LOG), "The log must be gathered");
					helper.assertFalse(helper.getBlockState(STONE_A).is(Blocks.STONE), "The first stone must be gathered");
					helper.assertFalse(helper.getBlockState(STONE_B).is(Blocks.STONE), "The second stone must be gathered");
					helper.assertFalse(helper.getBlockState(STONE_C).is(Blocks.STONE), "The third stone must be gathered");
					helper.assertFalse(first.getItemInHand(InteractionHand.MAIN_HAND).is(Items.STONE_PICKAXE), "No station means no pickaxe yet");
					helper.assertValueEqual(1, stockpile.countItem(Items.OAK_LOG), "The log must be communal before toolmaking");
					helper.assertValueEqual(3, stockpile.countItem(Items.COBBLESTONE), "All stone must be communal before toolmaking");
					helper.assertValueEqual(0, stockpile.countItem(Items.STICK) + first.getInventory().countItem(Items.STICK), "No sticks exist before crafting");

					helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
					player.setShiftKeyDown(true);
					useBlock(helper, player, CRAFTING_TABLE);
					player.setShiftKeyDown(false);
					helper.assertValueEqual(
						helper.absolutePos(CRAFTING_TABLE),
						data.getCraftingStation(settlement).orElseThrow(),
						"The crafting table must become the knapping station"
					);
					helper.runAfterDelay(300, () -> {
						runStockpileCycles(helper, data, settlement);
						helper.assertTrue(first.getItemInHand(InteractionHand.MAIN_HAND).is(Items.STONE_PICKAXE), "The first pickaxe must use communal materials");
						helper.assertValueEqual(0, stockpile.countItem(Items.OAK_LOG) + first.getInventory().countItem(Items.OAK_LOG), "The communal log must become sticks");
						helper.assertValueEqual(0, stockpile.countItem(Items.COBBLESTONE) + first.getInventory().countItem(Items.COBBLESTONE), "The pickaxe must consume three communal cobblestone");
						helper.assertValueEqual(6, stockpile.countItem(Items.STICK) + first.getInventory().countItem(Items.STICK), "Two of eight crafted sticks must be consumed");

						helper.setBlock(STONE_D, Blocks.STONE);
						player.setShiftKeyDown(true);
						useBlock(helper, player, STONE_D);
						player.setShiftKeyDown(false);
						helper.assertTrue(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_D)), "The fourth stone must be marked");
						helper.runAfterDelay(300, () -> {
							runStockpileCycles(helper, data, settlement);
							helper.assertFalse(helper.getBlockState(STONE_D).is(Blocks.STONE), "The fourth stone must be gathered with the tool");
							helper.assertTrue(stockpile.countItem(Items.COBBLESTONE) >= 1, "The chest must receive gathered cobblestone");
							helper.assertFalse(data.resourcesOf(settlement).contains(helper.absolutePos(LOG)), "The log marker must be removed");
							helper.assertFalse(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_A)), "The first stone marker must be removed");
							helper.assertFalse(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_B)), "The second stone marker must be removed");
							helper.assertFalse(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_C)), "The third stone marker must be removed");
							helper.assertFalse(data.resourcesOf(settlement).contains(helper.absolutePos(STONE_D)), "The fourth stone marker must be removed");
							beginConstructionAndGrinding(helper, player, data, settlement, first, stockpile);
						});
					});
				});
			});
		});
	}

	private static void beginConstructionAndGrinding(
		GameTestHelper helper,
		ServerPlayer player,
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		Container stockpile
	) {
		ConstructionPlan plan = BlueprintManager.get(ConstructionSystem.STONE_COTTAGE);
		helper.assertTrue(plan != null, "The JSON cottage blueprint must be loaded");
		for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			BlockPos origin = settlement.center().relative(direction, 6);
			for (ConstructionInstruction instruction : plan.instructions()) {
				helper.getLevel().removeBlock(origin.offset(instruction.offset()), false);
			}
		}
		SettlementStockpile.insert(stockpile, new ItemStack(Items.COBBLESTONE, 64));
		SettlementStockpile.insert(stockpile, new ItemStack(Items.OAK_PLANKS, 64));
		SettlementStockpile.insert(stockpile, new ItemStack(Items.OAK_DOOR));
		SettlementStockpile.insert(stockpile, new ItemStack(Items.WHITE_BED));
		SettlementStockpile.insert(stockpile, new ItemStack(Items.TORCH, 2));
		int initialCobblestone = stockpile.countItem(Items.COBBLESTONE);
		int initialPlanks = stockpile.countItem(Items.OAK_PLANKS);
		int initialSticks = stockpile.countItem(Items.STICK) + villager.getInventory().countItem(Items.STICK);
		helper.setBlock(GRINDING_STATION, Blocks.STONECUTTER);
		player.setShiftKeyDown(true);
		useBlock(helper, player, GRINDING_STATION);
		player.setShiftKeyDown(false);
		helper.assertValueEqual(
			helper.absolutePos(GRINDING_STATION),
			data.getGrindingStation(settlement).orElseThrow(),
			"The stonecutter must become the grinding station"
		);

		helper.succeedWhen(() -> {
			String constructionProgress = data.getConstructionJob(settlement)
				.map(job -> job.nextIndex() + "/" + plan.instructions().size() + " at " + job.origin())
				.orElse("not started");
			String workState = ((com.thysummer.coopcraft.mixin.MobAccessor) villager).getGoalSelector().getAvailableGoals().stream()
				.filter(prioritized -> prioritized.getGoal() instanceof VillagerWorkGoal)
				.map(prioritized -> ((VillagerWorkGoal) prioritized.getGoal()).debugState())
				.findFirst()
				.orElse("missing work goal");
			helper.assertTrue(
				data.housingCapacity(settlement) == 1,
				"Cottage incomplete: job=" + constructionProgress + ", villager=" + villager.blockPosition()
					+ ", cobblestone=" + stockpile.countItem(Items.COBBLESTONE) + ", planks=" + stockpile.countItem(Items.OAK_PLANKS)
					+ ", work=" + workState + ", testTick=" + helper.getTick()
			);
			BlockPos grindingStation = helper.absolutePos(GRINDING_STATION);
			if (villager.distanceToSqr(grindingStation.getCenter()) > 9.0) {
				villager.teleportTo(grindingStation.getX() + 0.5, grindingStation.getY() + 1.0, grindingStation.getZ() + 0.5);
				villager.getNavigation().stop();
			}
			ItemStack tool = villager.getItemInHand(InteractionHand.MAIN_HAND);
			helper.assertTrue(tool.is(Items.STONE_PICKAXE), "Polishing must retain a stone pickaxe");
			var enchantments = helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
			helper.assertValueEqual(1, tool.getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)), "The tool must have Efficiency I");
			helper.assertValueEqual(1, tool.getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.UNBREAKING)), "The tool must have Unbreaking I");
			helper.assertTrue(data.getConstructionJob(settlement).isEmpty(), "A completed cottage must clear its construction job");
			BlockPos origin = settlement.center().relative(Direction.NORTH, 6);
			for (ConstructionInstruction instruction : plan.instructions()) {
				BlockPos expected = origin.offset(instruction.offset());
				helper.assertTrue(
					helper.getLevel().getBlockState(expected).is(instruction.state().getBlock()),
					"Incorrect cottage block at " + instruction.offset()
				);
			}
			helper.assertTrue(helper.getLevel().getBlockState(origin.offset(2, 1, 0)).is(Blocks.OAK_DOOR), "The lower door must survive");
			helper.assertTrue(helper.getLevel().getBlockState(origin.offset(2, 2, 0)).is(Blocks.OAK_DOOR), "The upper door must survive");
			helper.assertTrue(helper.getLevel().getBlockState(origin.offset(2, 1, 3)).is(Blocks.WHITE_BED), "The bed foot must survive");
			helper.assertTrue(helper.getLevel().getBlockState(origin.offset(2, 1, 4)).is(Blocks.WHITE_BED), "The bed head must survive");
			int remainingCobblestone = initialCobblestone - plan.materials().get(Items.COBBLESTONE) - 3;
			int remainingPlanks = initialPlanks - plan.materials().get(Items.OAK_PLANKS);
			helper.assertValueEqual(remainingCobblestone, stockpile.countItem(Items.COBBLESTONE), "Construction and grinding must consume the exact cobblestone count");
			helper.assertValueEqual(remainingPlanks, stockpile.countItem(Items.OAK_PLANKS), "Construction must consume the exact plank count");
			helper.assertValueEqual(0, stockpile.countItem(Items.OAK_DOOR), "The door must come from the stockpile");
			helper.assertValueEqual(0, stockpile.countItem(Items.WHITE_BED), "The bed must come from the stockpile");
			helper.assertValueEqual(0, stockpile.countItem(Items.TORCH), "Both torches must come from the stockpile");
			helper.assertValueEqual(initialSticks - 2, stockpile.countItem(Items.STICK) + villager.getInventory().countItem(Items.STICK), "Polishing must consume two real sticks");
			helper.assertValueEqual(0, stockpile.countItem(Items.SMOOTH_STONE) + villager.getInventory().countItem(Items.SMOOTH_STONE), "Polishing must consume all three smooth stones");

			helper.runAfterDelay(200, () -> {
				helper.assertValueEqual(1, data.housingCapacity(settlement), "Housing must not be counted twice");
				helper.assertTrue(data.getConstructionJob(settlement).isEmpty(), "A completed cottage must not restart");
				helper.assertValueEqual(remainingCobblestone, stockpile.countItem(Items.COBBLESTONE), "An idle village must not consume cobblestone repeatedly");
				helper.assertValueEqual(remainingPlanks, stockpile.countItem(Items.OAK_PLANKS), "An idle village must not consume planks repeatedly");
				helper.assertValueEqual(initialSticks - 2, stockpile.countItem(Items.STICK) + villager.getInventory().countItem(Items.STICK), "A polished pickaxe must not be polished repeatedly");
				SettlementStockpile.insert(stockpile, new ItemStack(Items.BREAD, 4));
				data.setSatiety(settlement, villager.getUUID(), 10);
				int foodBeforeEating = stockpile.countItem(Items.BREAD) + villager.getInventory().countItem(Items.BREAD);
				helper.runAfterDelay(200, () -> {
					helper.assertTrue(data.needsOf(settlement, villager.getUUID()).satiety() > 10, "A hungry villager must eat real food");
					helper.assertTrue(foodBeforeEating > stockpile.countItem(Items.BREAD) + villager.getInventory().countItem(Items.BREAD), "Eating must consume real bread");
					helper.getLevel().getDataStorage().save();
					helper.succeed();
				});
			});
		});
	}

	private static void runStockpileCycles(GameTestHelper helper, SettlementSavedData data, Settlement settlement) {
		long gameTime = helper.getLevel().getGameTime();
		for (long offset = 0; offset <= 160; offset++) {
			SettlementStockpile.tick(helper.getLevel(), data, settlement, gameTime + offset);
		}
	}

	private static void useBlock(GameTestHelper helper, ServerPlayer player, BlockPos relativePos) {
		BlockPos pos = helper.absolutePos(relativePos);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, true);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
	}
}
