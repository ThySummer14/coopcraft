package com.thysummer.coopcraft.civilization;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementAutonomousMeetingSettingsTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void freshAndLegacyNormsEnabledStatesDefaultOff() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Council", 0);
		var state = data.getOrCreateSimulation(camp);
		assertTrue(state.autonomousMeetingsAvailable());
		assertFalse(state.autonomousMeetingsEnabled());
		state.setNormsEnabled(true);
		var old = state.save(); old.remove("AutonomousMeetings");
		var restored = SettlementSimulationState.load(camp, old);
		assertTrue(restored.normsEnabled());
		assertTrue(restored.autonomousMeetingsAvailable());
		assertFalse(restored.autonomousMeetingsEnabled());
	}

	@Test void bothValuesRoundTripAndIndependentVersionDetectsAbaOnlyOnRealChanges() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Council", 0);
		var state = data.getOrCreateSimulation(camp);
		long common = state.foodRequestModeVersion();
		state.setAutonomousMeetingsEnabled(false);
		assertEquals(0, state.autonomousMeetingsVersion());
		for (boolean enabled : List.of(true, false)) {
			state.setAutonomousMeetingsEnabled(enabled);
			var saved = state.save();
			var restored = SettlementSimulationState.load(camp, saved);
			assertTrue(restored.autonomousMeetingsAvailable());
			assertEquals(enabled, restored.autonomousMeetingsEnabled());
			assertEquals(saved, restored.save());
			state.setAutonomousMeetingsEnabled(enabled);
		}
		assertEquals(2, state.autonomousMeetingsVersion());
		assertEquals(common, state.foodRequestModeVersion());
	}

	@Test void malformedAndFutureDataArePreservedAndCannotBeOverwritten() {
		var data = new SettlementSavedData();
		var camp = data.getOrCreate(BlockPos.ZERO, "Council", 0);
		var state = data.getOrCreateSimulation(camp);
		state.setNormsEnabled(true);
		var future = setting(); future.putInt("Version", 2);
		var extra = setting(); extra.putString("Future", "preserve");
		var versionType = setting(); versionType.putByte("Version", (byte) 1);
		var enabledType = setting(); enabledType.putInt("Enabled", 1);
		var invalidByte = setting(); invalidByte.putByte("Enabled", (byte) 2);
		var negativeByte = setting(); negativeByte.putByte("Enabled", (byte) -1);
		var missing = setting(); missing.remove("Enabled");
		for (Tag raw : List.of(future, extra, versionType, enabledType, invalidByte, negativeByte, missing,
			new CompoundTag(), StringTag.valueOf("future"), IntTag.valueOf(1))) {
			var saved = state.save(); saved.put("AutonomousMeetings", raw.copy());
			var restored = SettlementSimulationState.load(camp, saved);
			assertFalse(restored.autonomousMeetingsAvailable());
			assertFalse(restored.autonomousMeetingsEnabled());
			restored.setAutonomousMeetingsEnabled(true);
			restored.setAutonomousMeetingsEnabled(false);
			assertEquals(0, restored.autonomousMeetingsVersion());
			assertEquals(saved, restored.save());
			assertTrue(restored.normsEnabled());
		}
	}
	private static CompoundTag setting() {
		var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putBoolean("Enabled", true); return tag;
	}
}
