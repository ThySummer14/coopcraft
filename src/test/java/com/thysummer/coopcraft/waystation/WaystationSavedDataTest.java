package com.thysummer.coopcraft.waystation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class WaystationSavedDataTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final UUID SETTLEMENT = UUID.randomUUID();

	@Test
	void addsAndFindsWaystations() {
		WaystationSavedData data = new WaystationSavedData();
		WaystationSavedData.Entry home = data.add(
			"晨星村驿站", OVERWORLD, new BlockPos(10, 64, -20),
			SETTLEMENT, "晨星村", UUID.randomUUID(), "Alice"
		);

		assertEquals(1, data.entries().size());
		assertEquals(Optional.of(home), data.find(home.id()));
		assertEquals(Optional.of(home), data.findAt(OVERWORLD, new BlockPos(10, 64, -20)));
		assertTrue(data.hasNear(OVERWORLD, new BlockPos(12, 64, -18), 8));
		assertFalse(data.hasNear(NETHER, new BlockPos(10, 64, -20), 8));
	}

	@Test
	void trimsLongAndBlankNames() {
		WaystationSavedData data = new WaystationSavedData();
		assertEquals("驿站", data.add("   ", OVERWORLD, new BlockPos(0, 64, 0),
			SETTLEMENT, "晨星村", UUID.randomUUID(), "Alice").name());

		String longest = "x".repeat(WaystationSavedData.MAX_NAME_LENGTH + 10);
		assertEquals(WaystationSavedData.MAX_NAME_LENGTH,
			data.add(longest, OVERWORLD, new BlockPos(5, 64, 5),
				SETTLEMENT, "晨星村", UUID.randomUUID(), "Alice").name().length());
	}

	@Test
	void removesById() {
		WaystationSavedData data = new WaystationSavedData();
		WaystationSavedData.Entry entry = data.add(
			"营地驿站", OVERWORLD, new BlockPos(3, 64, 3),
			SETTLEMENT, "营地", UUID.randomUUID(), "Bob"
		);

		assertTrue(data.remove(entry.id()));
		assertFalse(data.remove(entry.id()));
		assertTrue(data.entries().isEmpty());
	}

	@Test
	void survivesSaveAndLoad() {
		WaystationSavedData data = new WaystationSavedData();
		data.add("主世界驿站", OVERWORLD, new BlockPos(10, 64, -20),
			SETTLEMENT, "晨星村", UUID.randomUUID(), "Alice");
		data.add("下界驿站", NETHER, new BlockPos(10, 70, -20),
			SETTLEMENT, "晨星村", UUID.randomUUID(), "Bob");

		CompoundTag tag = data.save(new CompoundTag(), null);
		WaystationSavedData loaded = WaystationSavedData.loadForTest(tag);
		List<WaystationSavedData.Entry> entries = loaded.entries();

		assertEquals(2, entries.size());
		assertEquals("主世界驿站", entries.get(0).name());
		assertEquals(NETHER, entries.get(1).dimension());
		assertEquals(70, entries.get(1).pos().getY());

		WaystationSavedData.Entry third = loaded.add("矿洞驿站", OVERWORLD, new BlockPos(0, 20, 0),
			SETTLEMENT, "晨星村", UUID.randomUUID(), "Carol");
		assertTrue(third.id() > entries.get(1).id());
	}

	@Test
	void capsAtMaximum() {
		WaystationSavedData data = new WaystationSavedData();
		for (int i = 0; i < WaystationSavedData.MAX_WAYSTATIONS; i++) {
			data.add("p" + i, OVERWORLD, new BlockPos(i * 4, 64, 0),
				SETTLEMENT, "晨星村", UUID.randomUUID(), "Alice");
		}
		assertTrue(data.isFull());
		assertEquals(WaystationSavedData.MAX_WAYSTATIONS, data.entries().size());
	}
}
