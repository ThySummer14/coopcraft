package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic state/transfer tests with real Minecraft containers, not a running world or Mixin. */
class SettlementShearingTest {
	private static final UUID ACTOR = new UUID(980, 1), SHEEP = new UUID(981, 1);
	private static final BlockPos HOME = new BlockPos(0, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final class Fixture implements SettlementShearing.Probe {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(HOME, "Home", 0);
		SettlementSavedData current = data;
		Object actor = new Object();
		Vec3 actorPosition = new Vec3(0, 64, 0);
		final Map<UUID, SettlementShearing.Target> targets = new LinkedHashMap<>();
		ItemStack tool = new ItemStack(Items.SHEARS);
		SimpleContainer bag = new SimpleContainer(2);
		boolean active = true, demand = true, sight = true;
		long now;
		int reads;
		Runnable targetRead = () -> {}, sightRead = () -> {};
		Fixture() { data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, HOME.east()); add(SHEEP, 2); }
		void add(UUID id, double x) { data.permitShearing(camp, ACTOR, id); targets.put(id, target(id, new Object(), x, true, true)); }
		void move(double x) { targets.put(SHEEP, target(SHEEP, targets.get(SHEEP).identity(), x, true, true)); }
		SettlementShearing.Session session() { var s = prepare(); assertNotNull(s); return s; }
		SettlementShearing.Session prepare() { return SettlementShearing.prepare(data, camp, ACTOR, this, () -> demand); }
		public SettlementSavedData currentData() { return current; }
		public Object actorIdentity() { return actor; }
		public boolean activeAdultUnique() { return active; }
		public ItemStack mainHand() { return tool; }
		public long time() { return now; }
		public SettlementShearing.Target target(UUID id) { reads++; targetRead.run(); return targets.get(id); }
		public double distanceSquared(SettlementShearing.Target target) { return target.position().distanceToSqr(actorPosition); }
		public boolean lineOfSight(SettlementShearing.Target target) { sightRead.run(); return sight; }
		public SimpleContainer inventory() { return bag; }
	}
	private static SettlementShearing.Target target(UUID id, Object identity, double x, boolean ready, boolean loaded) {
		return new SettlementShearing.Target(id, identity, new Vec3(x, 64, 0), new AABB(x - .45, 64, -.45, x + .45, 65.3, .45), ready, loaded);
	}
	private static final class Drop implements SettlementShearing.Drop {
		final ItemStack stack;
		boolean current = true, discarded;
		int removed;
		Drop(int count) { this(new ItemStack(Items.WHITE_WOOL, count)); }
		Drop(ItemStack stack) { this.stack = stack; }
		public Object identity() { return this; }
		public boolean current() { return current && !discarded; }
		public ItemStack stack() { return stack; }
		public void removeAccepted(int count) { removed += count; stack.shrink(count); if (stack.isEmpty()) discarded = true; }
	}
	private static final class Action implements SettlementShearing.Action {
		final List<SettlementShearing.Drop> drops;
		boolean shorn, failBefore, failAfter;
		int shears, damage, events;
		Runnable callback = () -> {}, event = () -> {};
		Action(Drop... drops) { this.drops = List.of(drops); }
		public List<SettlementShearing.Drop> shear() {
			shears++; if (failBefore) throw new IllegalStateException("Before mutation");
			shorn = true; callback.run(); if (failAfter) throw new IllegalStateException("After mutation");
			return drops;
		}
		public boolean sheared() { return shorn; }
		public void damageTool() { damage++; }
		public void eventAndAnimation() { events++; event.run(); }
	}
	@Test void explicitPermitOnlyAndDiscoveryIsBoundedAndReadOnly() {
		Fixture f = new Fixture(); f.data.revokeShearing(f.camp, SHEEP);
		assertNull(f.prepare()); assertEquals(0, f.reads);
		for (int i = 0; i < 16; i++) f.add(new UUID(981, i + 10), 20 + i);
		CompoundTag before = f.data.save(new CompoundTag(), null);
		assertNull(f.prepare()); assertEquals(16, f.reads); assertEquals(before, f.data.save(new CompoundTag(), null));
	}
	@Test void liveTargetWorldBoundsColourAgeProtectionAndDemandAllGateSelection() {
		List<Consumer<Fixture>> invalid = List.of(
			f -> f.active = false, f -> f.demand = false, f -> f.current = new SettlementSavedData(),
			f -> f.move(12.01), f -> f.move(Double.NaN), f -> f.targets.clear(),
			f -> { f.actorPosition = new Vec3(57, 64, 0); f.move(65); },
			f -> f.targets.put(SHEEP, target(SHEEP, new Object(), 2, false, true)),
			f -> f.targets.put(SHEEP, target(SHEEP, new Object(), 2, true, false)),
			f -> f.data.addProtectedArea(ACTOR, "Edge", 1, 0, 1, 0),
			f -> f.data.removeMember(ACTOR));
		for (var change : invalid) { Fixture f = new Fixture(); change.accept(f); assertNull(f.prepare()); }
	}
	@Test void onlyActualSingleMainHandShearsWithNoPurposeMarkerCanStart() {
		for (ItemStack tool : List.of(ItemStack.EMPTY, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.SHEARS, 2))) {
			Fixture f = new Fixture(); f.tool = tool; assertNull(f.prepare());
		}
		for (String key : List.of(SettlementShearingCargo.KEY, SettlementWorkbenchCargo.KEY, ConstructionSupply.KEY)) {
			Fixture f = new Fixture(); CompoundTag root = new CompoundTag(); root.putString(key, "unknown");
			f.tool.set(DataComponents.CUSTOM_DATA, CustomData.of(root)); assertNull(f.prepare());
		}
		Fixture named = new Fixture(); named.tool.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this"));
		named.tool.setDamageValue(7); assertNotNull(named.prepare());
	}
	@Test void replacementComponentsPermitMemberStoreModeAndReloadCancelPermanently() {
		List<Consumer<Fixture>> changes = List.of(
			f -> f.tool = f.tool.copy(), f -> f.tool.setDamageValue(1),
			f -> f.tool.set(DataComponents.CUSTOM_NAME, Component.literal("Changed")),
			f -> f.actor = new Object(), f -> f.targets.put(SHEEP, target(SHEEP, new Object(), 2, true, true)),
			f -> { f.data.revokeShearing(f.camp, SHEEP); f.data.permitShearing(f.camp, ACTOR, SHEEP); },
			f -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); },
			f -> f.data.setStockpile(f.camp, HOME.east()),
			f -> f.current = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null),
			f -> f.data.getOrCreateSimulation(f.camp), f -> f.demand = false, f -> f.bag = new SimpleContainer(2));
		for (var change : changes) {
			Fixture f = new Fixture(); var s = f.session(); ItemStack tool = f.tool;
			change.accept(f); assertFalse(SettlementShearing.valid(s));
			f.tool = tool; f.current = f.data; f.demand = true;
			assertFalse(SettlementShearing.valid(s)); assertEquals(0, f.data.shearingLeases().size(f.now));
		}
		Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp); var s = f.session();
		state.setProductionEnabled(true); state.setProductionEnabled(false); assertFalse(SettlementShearing.valid(s));
	}
	@Test void unloadThenRestorationCannotReviveAndClockExpiryReleasesLease() {
		Fixture f = new Fixture(); var s = f.session(); var sheep = f.targets.remove(SHEEP);
		assertFalse(SettlementShearing.valid(s)); f.targets.put(SHEEP, sheep); assertFalse(SettlementShearing.valid(s));
		s = f.session(); f.now = 2400; assertFalse(SettlementShearing.valid(s)); assertEquals(0, f.data.shearingLeases().size(2400));
	}
	@Test void fullBagIncludingOrdinaryWoolAndOtherBatchCannotBeUsedAsThreeSlotCapacity() {
		Fixture f = new Fixture(); f.bag = new SimpleContainer(1); f.bag.setItem(0, new ItemStack(Items.WHITE_WOOL, 61));
		var s = f.session(); Action action = new Action(new Drop(3));
		assertEquals(0, SettlementShearing.finish(s, action)); assertEquals(0, action.shears); assertEquals(61, f.bag.countItem(Items.WHITE_WOOL));
		Fixture other = new Fixture(); other.bag = new SimpleContainer(1);
		other.bag.setItem(0, SettlementShearingCargo.mark(new ItemStack(Items.WHITE_WOOL, 61), other.camp.id(), UUID.randomUUID(), SHEEP, UUID.randomUUID()));
		assertEquals(0, SettlementShearing.finish(other.session(), new Action(new Drop(3))));
	}
	@Test void commitRechecksNearDistanceSightAndRevocationAfterCapacity() {
		for (boolean lineOfSight : List.of(false, true)) {
			Fixture f = new Fixture(); var s = f.session(); if (lineOfSight) f.move(3.01); else f.sight = false;
			Action action = new Action(new Drop(2)); assertEquals(0, SettlementShearing.finish(s, action)); assertEquals(0, action.shears);
		}
		Fixture moved = new Fixture(); var s = moved.session(); moved.sightRead = () -> moved.move(10);
		Action action = new Action(new Drop(2)); assertEquals(0, SettlementShearing.finish(s, action)); assertEquals(0, action.shears);
		Fixture revoke = new Fixture(); var ticket = revoke.session(); boolean[] armed = {true};
		revoke.bag.addListener(bag -> { if (armed[0]) revoke.data.revokeShearing(revoke.camp, SHEEP); });
		// Read callback during the final live target resolution must also cancel, before shear.
		revoke.targetRead = () -> { if (armed[0]) { armed[0] = false; revoke.data.revokeShearing(revoke.camp, SHEEP); } };
		Action blocked = new Action(new Drop(1)); assertEquals(0, SettlementShearing.finish(ticket, blocked)); assertEquals(0, blocked.shears);
	}
	@Test void exactActualOneToThreeDropsAreDetachedMarkedAndDebitedOnce() {
		for (int count = 1; count <= 3; count++) {
			Fixture f = new Fixture(); var s = f.session(); Drop drop = new Drop(count); Action action = new Action(drop);
			action.callback = () -> { assertTrue(s.consumed); assertFalse(SettlementShearing.valid(s)); };
			assertEquals(count, SettlementShearing.finish(s, action)); assertEquals(count, drop.removed); assertTrue(drop.discarded);
			assertEquals(count, f.bag.countItem(Items.WHITE_WOOL)); assertTrue(SettlementShearingCargo.isMarked(f.bag.getItem(0)));
			assertNotSame(drop.stack, f.bag.getItem(0)); assertEquals(1, action.damage); assertEquals(1, action.events);
			assertEquals(0, SettlementShearing.finish(s, action)); assertEquals(1, action.shears);
		}
	}
	@Test void threeSeparateVanillaDropsShareOneBatchAndDuplicateEntityIsRejected() {
		Fixture f = new Fixture(); Drop a = new Drop(1), b = new Drop(1), c = new Drop(1);
		assertEquals(3, SettlementShearing.finish(f.session(), new Action(a, b, c)));
		assertEquals(1, a.removed); assertEquals(1, b.removed); assertEquals(1, c.removed);
		assertEquals(3, f.bag.getItem(0).getCount()); assertTrue(f.bag.getItem(1).isEmpty());
		Fixture duplicate = new Fixture(); Drop same = new Drop(1);
		assertEquals(0, SettlementShearing.finish(duplicate.session(), new Action(same, same)));
		assertEquals(0, same.removed); assertTrue(duplicate.bag.isEmpty());
	}
	@Test void failedOrUnexpectedOutputNeverFabricatesRollsBackOrPaysTwice() {
		for (int failure = 0; failure < 6; failure++) {
			Fixture f = new Fixture(); var s = f.session(); Drop drop = new Drop(failure == 2 ? 4 : 2); Action action = new Action(drop);
			if (failure == 0) action.failBefore = true;
			if (failure == 1) action.failAfter = true;
			if (failure == 3) drop.current = false;
			if (failure == 4) drop.stack.set(DataComponents.CUSTOM_NAME, Component.literal("Unknown output"));
			if (failure == 5) action.event = () -> f.active = false;
			assertEquals(0, SettlementShearing.finish(s, action)); assertTrue(f.bag.isEmpty()); assertEquals(0, drop.removed);
			assertEquals(failure == 0 ? 0 : 1, action.damage); assertEquals(action.damage, action.events);
			assertEquals(failure != 0, action.shorn); assertEquals(0, SettlementShearing.finish(s, action));
		}
	}
	@Test void partialReceiptLeavesRemainderAndNeverClaimsNearbyOrRefusedEntities() {
		Fixture f = new Fixture(); var s = f.session(); Drop drop = new Drop(3); Action action = new Action(drop);
		action.callback = () -> {
			f.bag.setItem(0, SettlementShearingCargo.mark(new ItemStack(Items.WHITE_WOOL, 63), f.camp.id(), s.batch, SHEEP, s.permit.id()));
			f.bag.setItem(1, new ItemStack(Items.STONE, 64));
		};
		assertEquals(1, SettlementShearing.finish(s, action)); assertEquals(1, drop.removed); assertEquals(2, drop.stack.getCount()); assertFalse(drop.discarded);
		Fixture refused = new Fixture(); var ticket = refused.session(); Drop real = new Drop(1), rejected = new Drop(1); rejected.current = false;
		assertEquals(0, SettlementShearing.finish(ticket, new Action(real, rejected))); assertEquals(0, real.removed);
	}
	@Test void throwingChangeListenerStillSettlesAlreadyInsertedItemsExactlyOnce() {
		Fixture f = new Fixture(); var s = f.session(); Drop drop = new Drop(3);
		f.bag.addListener(bag -> { throw new IllegalStateException("Listener failed after write"); });
		assertEquals(3, SettlementShearing.finish(s, new Action(drop)));
		assertEquals(3, f.bag.countItem(Items.WHITE_WOOL)); assertEquals(3, drop.removed); assertTrue(drop.discarded);
	}
}
