package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled binding, crafting and construction services; does not run a villager or Minecraft level. */
@Isolated("BlueprintManager has a static reload snapshot")
class SettlementStarterWorkshopFlowTest {
	private static final UUID ACTOR = new UUID(54, 1);
	private static final BlockPos STORE = new BlockPos(1, 64, 0);
	private Field plans;
	private Object previous;
	private SettlementSavedData data;
	private Settlement settlement;
	private World world;
	private SimpleContainer stock;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void setup() throws Exception {
		plans = BlueprintManager.class.getDeclaredField("plans"); plans.setAccessible(true); previous = plans.get(null);
		var resources = new java.util.LinkedHashMap<net.minecraft.resources.ResourceLocation, com.google.gson.JsonElement>();
		for (String id : new String[] {"starter_workshop_v1", "timber_porch_cabin_v1", "campfire_hearth"}) {
			try (var reader = new InputStreamReader(getClass().getResourceAsStream(
				"/data/coopcraft/civilization/blueprints/" + id + ".json"), StandardCharsets.UTF_8)) {
				resources.put(net.minecraft.resources.ResourceLocation.parse("coopcraft:" + id), JsonParser.parseReader(reader));
			}
		}
		new BlueprintManager().apply(resources, null, null);
		data = new SettlementSavedData(); settlement = data.getOrCreate(new BlockPos(0, 64, 0), "Startup", 0);
		data.setStage(settlement, SettlementStage.AGRICULTURAL); data.registerMember(settlement, ACTOR, 0);
		data.setStockpile(settlement, STORE); stock = new SimpleContainer(9); world = new World();
		world.blocks.put(STORE, Blocks.BARREL.defaultBlockState()); world.identities.put(STORE, new Object());
	}
	@AfterEach void restore() throws Exception { plans.set(null, previous); }

	private static final class World implements SettlementCraftingStore.Probe, ConstructionStepReadiness.Probe {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, Object> identities = new HashMap<>();
		final Set<BlockPos> unknown = new HashSet<>();
		public boolean inBounds(BlockPos pos) { return pos.getY() >= -64 && pos.getY() < 320; }
		public boolean ticking(BlockPos pos) { return !unknown.contains(pos); }
		public boolean ready(BlockPos pos) { return inBounds(pos) && ticking(pos); }
		public BlockState state(BlockPos pos) {
			assertTrue(ready(pos));
			return blocks.getOrDefault(pos, pos.getY() == 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
		}
		public Object identity(BlockPos pos) { assertTrue(ready(pos)); return identities.get(pos); }
		public boolean entityObstructed(BlockPos pos) { return false; }
		public SettlementHousing.BedCheck inspectBed(BlockPos pos) { throw new AssertionError("No bed in starter workshop"); }
	}

	@Test void oneRealLogBecomesOneBuiltRegisteredWorkshopThroughExistingConstructionServices() {
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		for (int step = 0; step < 2; step++) {
			var binding = SettlementCraftingStore.capture(STORE, world); assertNotNull(binding);
			var ticket = SettlementStarterCrafting.prepare(data, settlement, ACTOR, stock, binding::valid);
			assertNotNull(ticket);
			assertEquals(step == 0 ? SettlementMaterialCrafting.Recipe.PLANKS : SettlementMaterialCrafting.Recipe.CRAFTING_TABLE, ticket.recipe());
			assertTrue(SettlementStarterCrafting.finish(ticket, data, settlement, ACTOR, stock));
		}
		assertEquals(0, stock.countItem(Items.OAK_LOG)); assertEquals(0, stock.countItem(Items.OAK_PLANKS));
		assertEquals(1, stock.countItem(Items.CRAFTING_TABLE)); assertTrue(data.getCraftingStation(settlement).isEmpty());
		var job = ConstructionSystem.chooseProject(data, settlement, stock,
			plan -> ConstructionSystem.chooseGuardedOrigin(data, settlement, plan, 0, world));
		assertNotNull(job); assertEquals(ConstructionPlanner.STARTER_WORKSHOP, job.blueprintId());
		data.setConstructionJob(settlement, job);
		assertFalse(SettlementStarterCrafting.eligible(data, settlement));
		var plan = BlueprintManager.get(job.blueprintId()); var part = plan.instructions().getFirst();
		assertTrue(ConstructionSystem.placeIfReady(job, plan, world, () -> ConstructionMaterials.place(
			stock, null, part.item(), part.consume(), job, ACTOR, data::setDirty,
			() -> { world.blocks.put(job.origin(), part.state()); return true; })));
		assertTrue(ConstructionWorkshop.registerCompleted(data, settlement, plan, job.origin(), world));
		data.completeConstruction(settlement);
		assertEquals(0, stock.countItem(Items.CRAFTING_TABLE));
		assertEquals(job.origin(), data.getCraftingStation(settlement).orElseThrow());
		assertEquals(1, world.blocks.values().stream().filter(s -> s.is(Blocks.CRAFTING_TABLE)).count());
		assertEquals(0, data.housingCapacity(settlement));
		assertNull(SettlementStarterCrafting.prepare(data, settlement, ACTOR, stock, () -> true));
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var housing = BlueprintManager.get(ConstructionPlanner.TIMBER_PORCH_CABIN);
		var recipe = SettlementMaterialCrafting.chooseRecipe(stock, null, ConstructionPlanner.remainingMaterials(housing, 0)).orElseThrow();
		assertEquals(SettlementMaterialCrafting.Recipe.PLANKS, recipe);
		assertTrue(SettlementMaterialCrafting.craft(stock, null, recipe, ACTOR, data::setDirty));
		assertEquals(4, stock.countItem(Items.OAK_PLANKS));
	}

	@Test void actualBindingCancelsReplacedStoreAndUnloadedDoubleChestBeforeAnyCraft() {
		stock.setItem(0, new ItemStack(Items.OAK_LOG));
		var binding = SettlementCraftingStore.capture(STORE, world); assertNotNull(binding);
		var ticket = SettlementStarterCrafting.prepare(data, settlement, ACTOR, stock, binding::valid);
		assertNotNull(ticket);
		world.blocks.put(STORE, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.OPEN, true));
		assertTrue(SettlementStarterCrafting.valid(ticket, data, settlement, ACTOR), "Opening the same barrel is ordinary interaction");
		world.identities.put(STORE, new Object());
		var before = data.save(new CompoundTag(), null); data.setDirty(false);
		assertFalse(SettlementStarterCrafting.finish(ticket, data, settlement, ACTOR, stock));
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
		var left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos partner = STORE.relative(ChestBlock.getConnectedDirection(left));
		world.blocks.put(STORE, left); world.blocks.put(partner, left.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		world.identities.put(partner, new Object());
		binding = SettlementCraftingStore.capture(STORE, world); assertNotNull(binding);
		ticket = SettlementStarterCrafting.prepare(data, settlement, ACTOR, stock, binding::valid); assertNotNull(ticket);
		world.unknown.add(partner);
		assertFalse(SettlementStarterCrafting.finish(ticket, data, settlement, ACTOR, stock));
		assertEquals(1, stock.countItem(Items.OAK_LOG)); assertEquals(0, stock.countItem(Items.OAK_PLANKS));
		assertEquals(before, data.save(new CompoundTag(), null)); assertFalse(data.isDirty());
	}

	@Test void newGoalActionHasIndependentReadableBilingualLabel() throws Exception {
		Class<?> action = Class.forName(VillagerWorkGoal.class.getName() + "$Action");
		var id = action.getDeclaredMethod("id"); id.setAccessible(true);
		String value = null;
		for (Object item : action.getEnumConstants()) {
			if (((Enum<?>) item).name().equals("CRAFT_FIRST_WORKBENCH")) value = (String) id.invoke(item);
		}
		assertEquals("craft_first_workbench", value);
		for (String language : new String[] {"en_us", "zh_cn"}) {
			try (var reader = new InputStreamReader(getClass().getResourceAsStream("/assets/coopcraft/lang/" + language + ".json"), StandardCharsets.UTF_8)) {
				var json = JsonParser.parseReader(reader).getAsJsonObject();
				String label = json.get("coopcraft.interaction.action." + value).getAsString();
				assertFalse(label.isBlank()); assertFalse(label.contains("%s"));
				assertNotEquals(json.get("coopcraft.interaction.action.craft_materials").getAsString(), label);
			}
		}
	}
}
