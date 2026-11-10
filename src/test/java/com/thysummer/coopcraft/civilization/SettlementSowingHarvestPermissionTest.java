package com.thysummer.coopcraft.civilization;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementSowingHarvestPermissionTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void permittedAndManualPlotsMergeWithoutDuplicateWorldReads() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.addSowingPlot(camp, BlockPos.ZERO);
		data.toggleResource(camp, BlockPos.ZERO);
		data.addSowingPlot(camp, BlockPos.ZERO.east());
		AtomicInteger reads = new AtomicInteger();
		assertEquals(BlockPos.ZERO, SettlementCropHarvest.findRipe(data, camp, BlockPos.ZERO,
			p -> true, p -> true, p -> { reads.incrementAndGet(); return ((CropBlock) Blocks.POTATOES).getStateForAge(7); }).orElseThrow());
		assertEquals(2, reads.get());
	}

	@Test void revokedThenRegrantedPermitCannotReviveSelectedHarvestOrSwitchToManual() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.addSowingPlot(camp, BlockPos.ZERO);
		var permission = SettlementCropHarvest.capturePermission(data, camp, BlockPos.ZERO);
		assertNotNull(permission);
		data.removeSowingPlot(camp, BlockPos.ZERO);
		data.addSowingPlot(camp, BlockPos.ZERO);
		data.toggleResource(camp, BlockPos.ZERO);
		assertFalse(SettlementCropHarvest.validTarget(data, camp, BlockPos.ZERO, permission,
			() -> { fail("Revoked authority must fail before world reads"); return true; },
			() -> true, () -> ((CropBlock) Blocks.POTATOES).getStateForAge(7)));
		assertTrue(SettlementCropHarvest.validTarget(data, camp, BlockPos.ZERO,
			() -> true, () -> true, () -> ((CropBlock) Blocks.POTATOES).getStateForAge(7)));
	}

	@Test void manualPermissionRemainsIndependentOfSowingRevocation() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.toggleResource(camp, BlockPos.ZERO);
		data.addSowingPlot(camp, BlockPos.ZERO);
		var permission = SettlementCropHarvest.capturePermission(data, camp, BlockPos.ZERO);
		assertTrue(permission.manual());
		data.removeSowingPlot(camp, BlockPos.ZERO);
		assertTrue(SettlementCropHarvest.permissionValid(permission, data, camp, BlockPos.ZERO));
		data.toggleResource(camp, BlockPos.ZERO);
		data.addSowingPlot(camp, BlockPos.ZERO);
		assertFalse(SettlementCropHarvest.permissionValid(permission, data, camp, BlockPos.ZERO));
	}

	@Test void revocationDuringLootPreventsReplantAndInventoryMutation() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		var actor = UUID.randomUUID();
		data.registerMember(camp, actor, 0);
		data.addSowingPlot(camp, BlockPos.ZERO);
		var permission = SettlementCropHarvest.capturePermission(data, camp, BlockPos.ZERO);
		var inventory = new SimpleContainer(2);
		assertEquals(0, SettlementCropHarvest.harvest(data, camp, actor, BlockPos.ZERO, permission, inventory,
			() -> true, () -> true, () -> true, () -> ((CropBlock) Blocks.POTATOES).getStateForAge(7),
			() -> { data.removeSowingPlot(camp, BlockPos.ZERO); data.addSowingPlot(camp, BlockPos.ZERO); return List.of(new ItemStack(Items.POTATO, 5)); },
			state -> { fail("Revoked harvest cannot replant"); return true; }));
		assertTrue(inventory.isEmpty());
	}

	@Test void sowingHarvestUsesItsOwnHorizontalRangeWithoutLegacyManualRadius() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		BlockPos pos = new BlockPos(60, 70, 0);
		data.addSowingPlot(camp, pos);
		assertTrue(data.find(pos).isEmpty());
		assertTrue(SettlementCropHarvest.validTarget(data, camp, pos, () -> true, () -> true,
			() -> ((CropBlock) Blocks.POTATOES).getStateForAge(7)));
		assertEquals(pos, SettlementCropHarvest.findRipe(data, camp, pos, p -> true, p -> true,
			p -> ((CropBlock) Blocks.POTATOES).getStateForAge(7)).orElseThrow());
	}

	@Test void unloadedPermitDoesNotReadBlockState() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Crops", 0);
		data.addSowingPlot(camp, BlockPos.ZERO);
		assertFalse(SettlementCropHarvest.validTarget(data, camp, BlockPos.ZERO, () -> false,
			() -> { fail("Not loaded"); return true; }, () -> { fail("Not loaded"); return Blocks.AIR.defaultBlockState(); }));
	}
}
