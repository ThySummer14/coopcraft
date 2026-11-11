package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.Map;

import com.google.gson.JsonParser;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("BlueprintManager has a static reload snapshot")
class ConstructionPlanRetentionTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:retention_fixture");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void missingPlanRetainsExistingJobWithoutWorldOrInventoryAccess() throws Exception {
		Field field = snapshotField();
		Object previous = field.get(null);
		try {
			new BlueprintManager().apply(Map.of(), null, null);
			assertRetained(1, "plan_unavailable");
		} finally {
			field.set(null, previous);
		}
	}

	@Test
	void indexAtOrBeyondPlanEndNeverCancelsOrCompletesExistingJob() throws Exception {
		Field field = snapshotField();
		Object previous = field.get(null);
		try {
			new BlueprintManager().apply(Map.of(ID, JsonParser.parseString(
				"{\"special\":[{\"pos\":[0,0,0],\"block\":\"cobblestone\"}]}")), null, null);
			int size = BlueprintManager.get(ID).instructions().size();
			assertRetained(size, "invalid_index");
			assertRetained(size + 1, "invalid_index");
			assertRetained(Integer.MAX_VALUE, "invalid_index");
		} finally {
			field.set(null, previous);
		}
	}

	private static void assertRetained(int index, String reason) {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(new BlockPos(2, 64, 2), "Retained construction", 0);
		var simulation = data.getOrCreateSimulation(settlement);
		simulation.setSurvivalEnabled(true, 0);
		simulation.setProductionEnabled(true);
		simulation.world().importExternalBatch(ResourceType.STONE, 8, simulation.campOwner());
		data.setStockpile(settlement, new BlockPos(3, 64, 2));
		var job = new ConstructionJob(ID, new BlockPos(6, 64, 2), index);
		data.setConstructionJob(settlement, job);
		var instruction = new ConstructionInstruction(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE, true);
		var staleTarget = new ConstructionSystem.BuildTarget(job, instruction, job.origin());
		var saved = data.save(new CompoundTag(), null);
		var simulated = simulation.save();
		data.setDirty(false);
		for (int attempt = 0; attempt < 3; attempt++) {
			// A null level proves neither the world nor its real stockpile can be accessed.
			assertTrue(ConstructionSystem.nextBuildTarget(null, data, settlement).isEmpty());
			assertEquals(reason, ConstructionSystem.inspect(null, data, settlement).orElseThrow().blocker());
			assertFalse(ConstructionSystem.buildNext(null, data, settlement, staleTarget, null));
			assertSame(job, data.getConstructionJob(settlement).orElseThrow());
			assertSame(simulation, data.simulationOf(settlement).orElseThrow());
			assertFalse(data.isDirty());
			assertEquals(simulated, simulation.save(), "No abstract inventory or ledger mutations");
			assertEquals(saved, data.save(new CompoundTag(), null));
		}
	}

	private static Field snapshotField() throws Exception {
		Field field = BlueprintManager.class.getDeclaredField("plans");
		field.setAccessible(true);
		return field;
	}
}
