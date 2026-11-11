package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

/** Actual ticket and inventory code with deterministic world probes; no running Minecraft world. */
class SettlementShearsLoanAuthorityTest {
	private static final UUID ACTOR = new UUID(992, 1), SHEEP = new UUID(993, 1);
	private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace("overworld");
	private static final BlockPos HOME = new BlockPos(0, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final class Fixture implements SettlementShearing.Probe, SettlementShearsLoan.Hand {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(HOME, "Home", 0);
		SettlementSavedData current = data;
		Object entity = new Object();
		ItemStack hand = ItemStack.EMPTY, offhand = new ItemStack(Items.TORCH);
		SimpleContainer bag = new SimpleContainer(3), store = new SimpleContainer(2);
		ResourceLocation dimension = DIMENSION;
		SettlementShearing.Target sheep = new SettlementShearing.Target(SHEEP, new Object(), new Vec3(2, 64, 0),
			new AABB(1.5, 64, -.5, 2.5, 65.5, .5), true, true);
		boolean active = true, demand = true, storeReady = true, arrived = true;
		long now;
		Runnable targetRead = () -> {};
		Fixture() {
			data.registerMember(camp, ACTOR, 0); data.setStockpile(camp, HOME.east()); data.permitShearing(camp, ACTOR, SHEEP);
			store.setItem(0, new ItemStack(Items.SHEARS));
		}
		public SettlementSavedData currentData() { return current; }
		public Object actorIdentity() { return entity; }
		public boolean activeAdultUnique() { return active; }
		public ItemStack mainHand() { return hand; }
		public ItemStack offHand() { return offhand; }
		public ResourceLocation dimension() { return dimension; }
		public long time() { return now; }
		public SettlementShearing.Target target(UUID id) { targetRead.run(); return SHEEP.equals(id) ? sheep : null; }
		public double distanceSquared(SettlementShearing.Target target) { return target.position().distanceToSqr(new Vec3(0, 64, 0)); }
		public boolean lineOfSight(SettlementShearing.Target target) { return true; }
		public SimpleContainer inventory() { return bag; }
		public ItemStack get() { return hand; }
		public void set(ItemStack stack) { hand = stack; }
		boolean candidate() { return SettlementShearing.hasBorrowCandidate(data, camp, ACTOR, this, () -> demand); }
		SettlementShearing.LoanTicket ticket(boolean returning) {
			return SettlementShearing.prepareLoan(data, camp, ACTOR, this, this, returning, () -> storeReady, () -> demand);
		}
		ItemStack loan() { return SettlementShearsLoan.mark(new ItemStack(Items.SHEARS), DIMENSION, camp.id(), ACTOR, UUID.randomUUID()); }
		boolean borrow(SettlementShearing.LoanTicket ticket) {
			return SettlementShearsLoan.borrow(store, this, DIMENSION, camp.id(), ACTOR,
				() -> SettlementShearing.validLoan(ticket) && arrived);
		}
		boolean giveBack(SettlementShearing.LoanTicket ticket) {
			return SettlementShearsLoan.returnFromHand(this, store, DIMENSION, camp.id(), ACTOR,
				() -> SettlementShearing.returnLoanAuthority(ticket) && arrived);
		}
	}

	@Test void candidateIsEmptyHandReadOnlyAndDoesNotClaimTheAvailableSheep() {
		Fixture f = new Fixture(); ItemStack source = f.store.getItem(0), offhand = f.offhand;
		CompoundTag saved = f.data.save(new CompoundTag(), null);
		assertTrue(f.candidate()); assertTrue(f.candidate()); assertTrue(f.hand.isEmpty());
		assertSame(source, f.store.getItem(0)); assertSame(offhand, f.offhand); assertTrue(f.bag.isEmpty());
		assertEquals(0, f.data.shearingLeases().size(f.now)); assertEquals(saved, f.data.save(new CompoundTag(), null));
		assertNull(SettlementShearing.prepare(f.data, f.camp, ACTOR, f, () -> true), "Candidate does not manufacture a tool");
	}

	@Test void preflightRequiresCurrentDemandPermitActorLoadedTargetAndNoLoanAnywhere() {
		List<Consumer<Fixture>> invalid = List.of(
			f -> f.hand = new ItemStack(Items.SHEARS), f -> f.hand = new ItemStack(Items.STONE_PICKAXE),
			f -> f.bag.setItem(1, f.loan()), f -> f.offhand = f.loan(),
			f -> f.demand = false, f -> f.active = false, f -> f.current = new SettlementSavedData(),
			f -> f.data.removeMember(ACTOR), f -> f.data.revokeShearing(f.camp, SHEEP),
			f -> f.data.addProtectedArea(ACTOR, "Sheep", 1, -1, 3, 1), f -> f.sheep = null,
			f -> f.sheep = new SettlementShearing.Target(SHEEP, new Object(), new Vec3(2, 64, 0), new AABB(1,64,0,3,66,1), true, false),
			f -> f.sheep = new SettlementShearing.Target(SHEEP, new Object(), new Vec3(13, 64, 0), new AABB(12,64,0,14,66,1), true, true));
		for (var change : invalid) {
			Fixture f = new Fixture(); change.accept(f); assertFalse(f.candidate()); assertNull(f.ticket(false));
			assertEquals(0, f.data.shearingLeases().size(f.now));
		}
		Fixture unknown = new Fixture(); CompoundTag root = new CompoundTag(); root.putString(SettlementShearsLoan.KEY, "future");
		ItemStack cargo = new ItemStack(Items.STONE); cargo.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		unknown.bag.setItem(0, cargo); assertFalse(unknown.candidate());
	}

	@Test void capacityPreviewRefusesFullPersonalBagWithoutAnyRealInventoryMutation() {
		Fixture f = new Fixture(); f.bag = new SimpleContainer(8);
		for (int slot = 0; slot < 8; slot++) f.bag.setItem(slot, new ItemStack(Items.STONE, 64));
		int[] notifications = {0}; f.bag.addListener(ignored -> notifications[0]++);
		ItemStack[] originals = new ItemStack[8]; for (int slot = 0; slot < 8; slot++) originals[slot] = f.bag.getItem(slot);
		assertFalse(f.candidate()); assertNull(f.ticket(false)); assertEquals(0, notifications[0]);
		for (int slot = 0; slot < 8; slot++) { assertSame(originals[slot], f.bag.getItem(slot)); assertEquals(64, originals[slot].getCount()); }
		f.bag.setItem(0, new ItemStack(Items.WHITE_WOOL, 61)); assertFalse(f.candidate(), "Personal wool must not absorb a fresh cargo marker");
		f.bag.setItem(0, ItemStack.EMPTY); int before = notifications[0];
		assertTrue(f.candidate()); assertNotNull(f.ticket(false)); assertEquals(before, notifications[0]);
		var ticket = f.ticket(false); f.bag.setItem(0, new ItemStack(Items.STONE, 64));
		assertFalse(f.borrow(ticket)); assertTrue(f.hand.isEmpty()); assertTrue(f.store.getItem(0).is(Items.SHEARS));
	}

	@Test void clockWrapOrLargeElapsedTimeCannotReviveBorrowOrReturnAuthority() {
		for (boolean returning : List.of(false, true)) {
			Fixture f = new Fixture(); if (returning) f.hand = f.loan();
			f.now = returning ? Long.MAX_VALUE : Long.MAX_VALUE - SettlementShearingLeases.TTL_TICKS - 1;
			long created = f.now; var ticket = f.ticket(returning); assertNotNull(ticket);
			f.now = Long.MIN_VALUE; assertFalse(SettlementShearing.validLoan(ticket));
			f.now = created; assertFalse(SettlementShearing.validLoan(ticket));
			assertFalse(SettlementShearing.returnLoanAuthority(ticket));
			Fixture huge = new Fixture(); if (returning) huge.hand = huge.loan();
			huge.now = returning ? Long.MIN_VALUE : 0; var ancient = huge.ticket(returning); assertNotNull(ancient);
			huge.now = Long.MAX_VALUE; assertFalse(SettlementShearing.validLoan(ancient));
		}
	}

	@Test void candidateRechecksRevocationAndIdentityChangesDuringDiscovery() {
		Fixture revoke = new Fixture(); revoke.targetRead = () -> revoke.data.revokeShearing(revoke.camp, SHEEP);
		assertFalse(revoke.candidate());
		Fixture replaced = new Fixture(); replaced.targetRead = () -> replaced.entity = new Object();
		assertFalse(replaced.candidate());
		Fixture changedBag = new Fixture(); changedBag.targetRead = () -> changedBag.bag = new SimpleContainer(3);
		assertFalse(changedBag.candidate());
	}

	@Test void travelAuthorityBindsAllIdentityFieldsAndDoesNotReviveAfterFailure() {
		List<Consumer<Fixture>> invalid = List.of(
			f -> f.current = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null),
			f -> f.entity = new Object(), f -> f.bag = new SimpleContainer(3),
			f -> { f.data.removeMember(ACTOR); f.data.registerMember(f.camp, ACTOR, 0); },
			f -> f.data.setStockpile(f.camp, HOME.east()), f -> f.storeReady = false,
			f -> f.dimension = ResourceLocation.withDefaultNamespace("the_nether"),
			f -> f.data.getOrCreateSimulation(f.camp), f -> f.now = SettlementShearing.LoanTicket.MAX_AGE,
			f -> f.now = -1, f -> f.demand = false, f -> f.active = false,
			f -> f.hand = new ItemStack(Items.TORCH), f -> f.bag.setItem(2, f.loan()));
		for (var change : invalid) {
			Fixture f = new Fixture(); var ticket = f.ticket(false); assertNotNull(ticket);
			change.accept(f); assertFalse(SettlementShearing.validLoan(ticket));
			f.current = f.data; f.storeReady = true; f.active = true; f.demand = true; f.now = 0; f.hand = ItemStack.EMPTY;
			assertFalse(SettlementShearing.validLoan(ticket)); assertFalse(f.borrow(ticket));
			assertTrue(f.store.getItem(0).is(Items.SHEARS));
		}
		Fixture mode = new Fixture(); var simulation = mode.data.getOrCreateSimulation(mode.camp); var ticket = mode.ticket(false);
		assertNotNull(ticket); simulation.setProductionEnabled(true); simulation.setProductionEnabled(false);
		assertFalse(SettlementShearing.validLoan(ticket));
	}

	@Test void pickupRechecksArrivalDemandAndPermitWithoutRemovingWarehouseTool() {
		for (Consumer<Fixture> change : List.<Consumer<Fixture>>of(f -> f.arrived = false, f -> f.demand = false,
			f -> f.data.revokeShearing(f.camp, SHEEP), f -> f.storeReady = false)) {
			Fixture f = new Fixture(); var ticket = f.ticket(false); assertNotNull(ticket); ItemStack source = f.store.getItem(0);
			change.accept(f); assertFalse(f.borrow(ticket)); assertSame(source, f.store.getItem(0)); assertTrue(f.hand.isEmpty());
		}
	}

	@Test void actualPickupAllowsOnlyOwnedToolAndOneSessionThenReturnPreservesDamageAndComponents() {
		Fixture f = new Fixture(); ItemStack source = f.store.getItem(0), personalOffhand = f.offhand;
		source.setDamageValue(7); source.set(DataComponents.CUSTOM_NAME, Component.literal("Workshop tool"));
		var borrowing = f.ticket(false); assertNotNull(borrowing); assertTrue(f.borrow(borrowing));
		assertSame(source, f.hand); assertTrue(f.store.getItem(0).isEmpty()); assertSame(personalOffhand, f.offhand);
		assertFalse(f.borrow(borrowing)); SettlementShearing.cancelLoan(borrowing);
		var session = SettlementShearing.prepare(f.data, f.camp, ACTOR, f, () -> f.demand); assertNotNull(session);
		int[] shears = {0};
		var action = new SettlementShearing.Action() {
			public List<SettlementShearing.Drop> shear() { shears[0]++; return List.of(); }
			public boolean sheared() { return shears[0] > 0; }
			public void damageTool() { f.hand.setDamageValue(f.hand.getDamageValue() + 1); }
			public void eventAndAnimation() { }
		};
		SettlementShearing.finish(session, action); SettlementShearing.finish(session, action);
		assertEquals(1, shears[0]); assertEquals(8, source.getDamageValue());
		f.demand = false; f.data.revokeShearing(f.camp, SHEEP);
		var returning = f.ticket(true); assertNotNull(returning); assertTrue(f.giveBack(returning));
		assertTrue(f.hand.isEmpty()); assertSame(source, f.store.getItem(0)); assertSame(personalOffhand, f.offhand);
		assertFalse(SettlementShearsLoan.isMarked(source)); assertEquals(8, source.getDamageValue());
		assertEquals(Component.literal("Workshop tool"), source.get(DataComponents.CUSTOM_NAME));
	}

	@Test void ordinaryShearsRemainUsableWhileUnknownForeignAndWrongDimensionLoansCannotShear() {
		Fixture ordinary = new Fixture(); ordinary.hand = new ItemStack(Items.SHEARS);
		assertNotNull(SettlementShearing.prepare(ordinary.data, ordinary.camp, ACTOR, ordinary, () -> true));
		for (int change = 0; change < 4; change++) {
			Fixture f = new Fixture();
			f.hand = SettlementShearsLoan.mark(new ItemStack(Items.SHEARS), change == 0 ? ResourceLocation.withDefaultNamespace("the_nether") : DIMENSION,
				change == 1 ? UUID.randomUUID() : f.camp.id(), change == 2 ? UUID.randomUUID() : ACTOR, UUID.randomUUID());
			if (change == 3) { CompoundTag tag = f.hand.get(DataComponents.CUSTOM_DATA).copyTag(); tag.putString(SettlementShearsLoan.KEY, "future"); f.hand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); }
			assertNull(SettlementShearing.prepare(f.data, f.camp, ACTOR, f, () -> true)); assertNull(f.ticket(true));
		}
	}

	@Test void returnIsIndependentOfOriginalGrantButStillNeedsMembershipStoreArrivalAndCapacity() {
		Fixture f = new Fixture(); f.hand = f.loan(); f.store.setItem(0, ItemStack.EMPTY);
		var old = f.ticket(true); assertNotNull(old); SettlementShearing.cancelLoan(old);
		f.demand = false; f.data.revokeShearing(f.camp, SHEEP); f.data.addProtectedArea(ACTOR, "Sheep", 1, -1, 3, 1);
		assertFalse(f.giveBack(old)); var ticket = f.ticket(true); assertNotNull(ticket);
		f.arrived = false; assertFalse(f.giveBack(ticket)); assertTrue(SettlementShearsLoan.isMarked(f.hand));
		f.arrived = true; f.store.setItem(0, new ItemStack(Items.STONE, 64)); f.store.setItem(1, new ItemStack(Items.STONE, 64));
		assertFalse(f.giveBack(ticket)); assertTrue(SettlementShearsLoan.isMarked(f.hand));
		f.store.setItem(1, ItemStack.EMPTY); assertTrue(f.giveBack(ticket));
		for (Consumer<Fixture> invalid : List.<Consumer<Fixture>>of(g -> g.data.removeMember(ACTOR), g -> g.active = false, g -> g.storeReady = false)) {
			Fixture g = new Fixture(); g.hand = g.loan(); var t = g.ticket(true); assertNotNull(t); invalid.accept(g);
			assertFalse(g.giveBack(t)); assertTrue(SettlementShearsLoan.isMarked(g.hand));
		}
	}

	@Test void detachedReturnRecheckDoesNotResurrectAnInvalidTicketOrClearAReplacement() {
		Fixture f = new Fixture(); f.hand = f.loan(); var ticket = f.ticket(true); assertNotNull(ticket);
		f.hand = ItemStack.EMPTY; assertTrue(SettlementShearing.returnLoanAuthority(ticket), "Core is allowed to detach the pinned tool during return");
		f.storeReady = false; assertFalse(SettlementShearing.returnLoanAuthority(ticket)); f.storeReady = true;
		f.hand = ticket.returningTool; assertFalse(SettlementShearing.returnLoanAuthority(ticket));
		Fixture replaced = new Fixture(); replaced.hand = replaced.loan(); var old = replaced.ticket(true); assertNotNull(old);
		replaced.hand = replaced.loan(); assertFalse(SettlementShearing.returnLoanAuthority(old));
	}

	@Test void returnRevalidatesBindingAfterSourceClearAndRollsBackTheSameTool() {
		Fixture f = new Fixture(); f.hand = f.loan(); ItemStack original = f.hand;
		SettlementShearsLoan.Hand source = new SettlementShearsLoan.Hand() {
			public ItemStack get() { return f.hand; }
			public void set(ItemStack stack) { f.hand = stack; if (stack.isEmpty()) f.storeReady = false; }
		};
		var ticket = SettlementShearing.prepareLoan(f.data, f.camp, ACTOR, f, source, true, () -> f.storeReady, () -> false);
		assertNotNull(ticket);
		assertFalse(SettlementShearsLoan.returnFromHand(source, f.store, DIMENSION, f.camp.id(), ACTOR,
			() -> SettlementShearing.returnLoanAuthority(ticket) && f.arrived));
		assertSame(original, f.hand); assertTrue(SettlementShearsLoan.isMarked(f.hand)); assertTrue(f.store.getItem(1).isEmpty());
		f.storeReady = true; assertFalse(SettlementShearing.validLoan(ticket));
	}

	@Test void borrowRevalidatesPermitAfterSourceDebitAndRestoresTheSameWarehouseTool() {
		Fixture f = new Fixture();
		f.store = new SimpleContainer(2) {
			@Override public ItemStack removeItemNoUpdate(int slot) {
				ItemStack detached = super.removeItemNoUpdate(slot); f.data.revokeShearing(f.camp, SHEEP); return detached;
			}
		};
		ItemStack original = new ItemStack(Items.SHEARS); f.store.setItem(0, original);
		var ticket = f.ticket(false); assertNotNull(ticket); assertFalse(f.borrow(ticket));
		assertTrue(f.hand.isEmpty()); assertSame(original, f.store.getItem(0)); assertFalse(SettlementShearsLoan.isMarked(original));
		f.data.permitShearing(f.camp, ACTOR, SHEEP); assertFalse(SettlementShearing.validLoan(ticket));
	}

	@Test void bagLoanReturnsThroughItsOwnSlotWithoutSwappingPersonalEquipment() {
		Fixture f = new Fixture(); f.hand = new ItemStack(Items.STONE_PICKAXE); ItemStack hand = f.hand, offhand = f.offhand;
		ItemStack loan = f.loan(); f.bag.setItem(1, loan);
		SettlementShearsLoan.Hand source = new SettlementShearsLoan.Hand() {
			public ItemStack get() { return f.bag.getItem(1); }
			public void set(ItemStack stack) { f.bag.setItem(1, stack); }
		};
		var ticket = SettlementShearing.prepareLoan(f.data, f.camp, ACTOR, f, source, true, () -> f.storeReady, () -> false);
		assertNotNull(ticket);
		assertTrue(SettlementShearsLoan.returnFromHand(source, f.store, DIMENSION, f.camp.id(), ACTOR,
			() -> SettlementShearing.returnLoanAuthority(ticket) && f.arrived));
		assertSame(hand, f.hand); assertSame(offhand, f.offhand); assertTrue(f.bag.getItem(1).isEmpty());
		assertSame(loan, f.store.getItem(1)); assertFalse(SettlementShearsLoan.isMarked(loan));
	}
}
