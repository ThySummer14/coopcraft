package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real container/service boundaries only; no entity, world, navigation or GameTest execution. */
class MinecraftMaterialTransferFailureTest {
	private static final UUID ACTOR = new UUID(63, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static SettlementSimulationState state() {
		var state = SettlementSimulationState.create(new Settlement(new UUID(63, 2), BlockPos.ZERO, "Materials", 0));
		state.mapResident(ACTOR, false, 0); state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		return state;
	}
	private static class Tracked extends SimpleContainer {
		int reads, writes;
		Tracked(int slots) { super(slots); }
		@Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
		@Override public void setItem(int slot, ItemStack stack) { writes++; super.setItem(slot, stack); }
		@Override public ItemStack removeItem(int slot, int count) { writes++; return super.removeItem(slot, count); }
		@Override public void setChanged() { writes++; super.setChanged(); }
	}
	private record Metadata(List<String> actors, Map<String, String> context, List<Long> causes) { }
	private static List<Metadata> invalidMetadata() {
		Map<String, String> nullValue = new LinkedHashMap<>(); nullValue.put("activity", null);
		Map<String, String> nullKey = new LinkedHashMap<>(); nullKey.put(null, "build");
		return List.of(new Metadata(null, Map.of(), List.of()),
			new Metadata(Arrays.asList("builder", null), Map.of(), List.of()),
			new Metadata(List.of(" "), Map.of(), List.of()),
			new Metadata(List.of(), null, List.of()), new Metadata(List.of(), nullKey, List.of()),
			new Metadata(List.of(), nullValue, List.of()), new Metadata(List.of(), Map.of("", "build"), List.of()));
	}
	private static void rejectedBeforeContainer(SettlementSimulationState state, Tracked inventory, Runnable operation) {
		var worldBefore = state.save(); long nextBatch = state.world().nextBatchId();
		List<ItemStack> before = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) before.add(inventory.getItem(i).copy());
		inventory.reads = inventory.writes = 0;
		assertThrows(IllegalArgumentException.class, operation::run);
		assertEquals(0, inventory.reads, "Preflight must reject before reading container contents");
		assertEquals(0, inventory.writes, "No speculative item movement or rollback");
		assertEquals(worldBefore, state.save()); assertEquals(nextBatch, state.world().nextBatchId());
		for (int i = 0; i < before.size(); i++) assertTrue(ItemStack.matches(before.get(i), inventory.getItem(i)));
		state.world().assertConservation();
	}
	private static void field(Object object, String name, long value) throws Exception {
		Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(object, value);
	}

	@Test void importMetadataFailureCannotLeaveBothRestoredItemsAndAnImportedBatch() {
		var state = state(); Tracked source = new Tracked(3);
		source.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		ItemStack named = new ItemStack(Items.COBBLESTONE, 4); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
		source.setItem(2, named);
		for (Metadata meta : invalidMetadata()) rejectedBeforeContainer(state, source, () ->
			MinecraftStockpileAdapter.importToSimulation(source, state.world(), state.campOwner(), ResourceType.STONE, 2,
				meta.actors(), meta.context(), meta.causes()));
		assertEquals(2, MinecraftStockpileAdapter.importToSimulation(source, state.world(), state.campOwner(), ResourceType.STONE, 2));
		assertEquals(5, source.countItem(Items.COBBLESTONE)); assertEquals(1, state.world().batches().size());
		assertEquals(2, state.world().batches().getFirst().quantity());
		assertEquals(1, state.world().ledger().events().size()); state.world().assertConservation();
	}

	@Test void importRejectsInvalidCausalReferencesWithoutTouchingSource() {
		var state = state(); Tracked source = new Tracked(1); source.setItem(0, new ItemStack(Items.OAK_LOG, 4));
		List<List<Long>> causes = new ArrayList<>(); causes.add(null); causes.add(Arrays.asList((Long) null));
		causes.add(List.of(0L)); causes.add(List.of(1L)); causes.add(List.of(Long.MAX_VALUE));
		for (var links : causes) rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.importToSimulation(
			source, state.world(), state.campOwner(), ResourceType.WOOD, 1, List.of(), Map.of(), links));
	}

	@Test void partialAndWholeExportsRejectBeforeChangingEitherAuthority() {
		for (int amount : List.of(2, 5)) for (Metadata meta : invalidMetadata()) {
			var state = state(); var batch = state.world().importExternalBatch(ResourceType.STONE, 5, state.campOwner());
			Tracked target = new Tracked(3); target.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
			target.setItem(2, new ItemStack(Items.STICK, 4));
			rejectedBeforeContainer(state, target, () -> MinecraftStockpileAdapter.exportToContainer(
				state.world(), state.campOwner(), batch.batchId(), amount, target, meta.actors(), meta.context()));
			assertEquals(amount, MinecraftStockpileAdapter.exportToContainer(state.world(), state.campOwner(), batch.batchId(), amount, target));
			assertEquals(63 + amount, target.countItem(Items.COBBLESTONE));
			assertEquals(5 - amount, state.world().inventoryTotals().get(ResourceType.STONE).longValue());
			assertEquals(2, state.world().ledger().events().size()); state.world().assertConservation();
		}
	}

	@Test void invalidInputsAndWrongAuthorityNeverInspectTheContainer() {
		var state = state(); Tracked source = new Tracked(1); source.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.importToSimulation(source, state.world(), state.campOwner(), null, 1));
		rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.importToSimulation(source, state.world(), state.campOwner(), ResourceType.STONE, 0));
		rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.importToSimulation(source, state.world(), null, ResourceType.STONE, 1));
		var batch = state.world().importExternalBatch(ResourceType.STONE, 3, state.campOwner());
		rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.exportToContainer(state.world(), StorageOwner.camp("missing"), batch.batchId(), 1, source));
		rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.exportToContainer(state.world(), state.campOwner(), batch.batchId(), 0, source));
	}

	@Test void exhaustedImportCountersRejectBeforeAnyContainerMovement() throws Exception {
		for (boolean batchCounter : List.of(false, true)) {
			var state = state(); Tracked source = new Tracked(1); source.setItem(0, new ItemStack(Items.STICK, 3));
			Object target = batchCounter ? state.world() : state.world().ledger();
			String name = batchCounter ? "nextBatchId" : "nextEventId"; field(target, name, Long.MAX_VALUE);
			rejectedBeforeContainer(state, source, () -> MinecraftStockpileAdapter.importToSimulation(source, state.world(), state.campOwner(), ResourceType.STICK, 1));
			field(target, name, 1);
			assertEquals(1, MinecraftStockpileAdapter.importToSimulation(source, state.world(), state.campOwner(), ResourceType.STICK, 1));
			assertEquals(2, source.countItem(Items.STICK)); state.world().assertConservation();
		}
	}

	@Test void constructionExportFailureDoesNotPlaceOrLoseAbstractMaterial() throws Exception {
		var state = state(); state.world().importExternalBatch(ResourceType.STONE, 3, state.campOwner());
		Tracked store = new Tracked(1); AtomicInteger placements = new AtomicInteger(), dirty = new AtomicInteger();
		var job = new ConstructionJob(ConstructionPlanner.CAMPFIRE_HEARTH, new BlockPos(6, 64, 0), 2);
		field(state.world().ledger(), "nextEventId", Long.MAX_VALUE);
		var before = state.save();
		assertThrows(IllegalArgumentException.class, () -> ConstructionMaterials.place(store, state, Items.COBBLESTONE, true,
			job, ACTOR, dirty::incrementAndGet, () -> { placements.incrementAndGet(); return true; }));
		assertEquals(before, state.save()); assertTrue(store.isEmpty()); assertEquals(0, store.writes);
		assertEquals(0, placements.get()); assertEquals(0, dirty.get());
		field(state.world().ledger(), "nextEventId", 2);
		assertTrue(ConstructionMaterials.place(store, state, Items.COBBLESTONE, true, job, ACTOR, dirty::incrementAndGet,
			() -> { placements.incrementAndGet(); return true; }));
		assertEquals(1, placements.get()); assertEquals(1, dirty.get()); assertTrue(store.isEmpty());
		assertEquals(2, state.world().inventoryTotals().get(ResourceType.STONE).longValue());
		assertEquals(1, state.world().ledger().events().stream().filter(e -> e.type() == EventType.EXTERNAL_EXPORT).count());
		state.world().assertConservation();
	}
}
