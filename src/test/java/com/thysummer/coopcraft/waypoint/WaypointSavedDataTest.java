package com.thysummer.coopcraft.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.thysummer.coopcraft.net.WaypointListPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class WaypointSavedDataTest {
	private static final String OVERWORLD = "minecraft:overworld";

	@Test
	void addsAndFindsWaypoints() {
		WaypointSavedData data = new WaypointSavedData();
		WaypointListPayload.Entry home = data.add("家", OVERWORLD, new BlockPos(10, 64, -20), "Alice");

		assertEquals(1, data.entries().size());
		assertEquals(Optional.of(home), data.find(home.id()));
		assertEquals("家", data.entries().get(0).name());
		assertEquals(OVERWORLD, data.entries().get(0).dimension());
		assertEquals(-20, data.entries().get(0).z());
	}

	@Test
	void rejectsDuplicateSpotsButKeysOnDimension() {
		WaypointSavedData data = new WaypointSavedData();
		data.add("矿洞", OVERWORLD, new BlockPos(100, 30, 100), "Alice");

		assertTrue(data.findNear(OVERWORLD, new BlockPos(101, 30, 100)).isPresent());
		// 同样的坐标在下界是另一个地方。
		assertFalse(data.findNear("minecraft:the_nether", new BlockPos(100, 30, 100)).isPresent());
	}

	@Test
	void trimsLongAndBlankNames() {
		WaypointSavedData data = new WaypointSavedData();
		assertEquals("?", data.add("   ", OVERWORLD, new BlockPos(0, 64, 0), "Alice").name());

		String longest = "x".repeat(WaypointSavedData.MAX_NAME_LENGTH + 10);
		assertEquals(WaypointSavedData.MAX_NAME_LENGTH,
			data.add(longest, OVERWORLD, new BlockPos(5, 64, 5), "Alice").name().length());
	}

	@Test
	void removesById() {
		WaypointSavedData data = new WaypointSavedData();
		WaypointListPayload.Entry entry = data.add("营地", OVERWORLD, new BlockPos(3, 64, 3), "Bob");

		assertTrue(data.remove(entry.id()));
		assertFalse(data.remove(entry.id()));
		assertTrue(data.entries().isEmpty());
	}

	@Test
	void survivesSaveAndLoad() {
		WaypointSavedData data = new WaypointSavedData();
		data.add("家", OVERWORLD, new BlockPos(10, 64, -20), "Alice");
		data.add("下界门", "minecraft:the_nether", new BlockPos(10, 70, -20), "Bob");

		CompoundTag tag = data.save(new CompoundTag(), null);
		WaypointSavedData loaded = WaypointSavedData.loadForTest(tag);
		List<WaypointListPayload.Entry> entries = loaded.entries();

		assertEquals(2, entries.size());
		assertEquals("家", entries.get(0).name());
		assertEquals("minecraft:the_nether", entries.get(1).dimension());
		assertEquals(70, entries.get(1).y());

		// 读回来以后继续加，id 不能和已有的撞。
		WaypointListPayload.Entry third = loaded.add("矿洞", OVERWORLD, new BlockPos(0, 20, 0), "Carol");
		assertTrue(third.id() > entries.get(1).id());
	}

	@Test
	void capsAtMaximum() {
		WaypointSavedData data = new WaypointSavedData();
		for (int i = 0; i < WaypointSavedData.MAX_WAYPOINTS; i++) {
			data.add("p" + i, OVERWORLD, new BlockPos(i * 4, 64, 0), "Alice");
		}
		assertTrue(data.isFull());
		assertEquals(WaypointSavedData.MAX_WAYPOINTS, data.entries().size());
	}
}
