package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.thysummer.coopcraft.civilization.SettlementMembershipClaims.*;

/** Shared production predicates and persisted-data reads, not a live world or packet test. */
class ResidentDialogValidationTest {
	private static final UUID RESIDENT = new UUID(82, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private ResidentDialogService.Presence presence(int missing, double distance) {
		return new ResidentDialogService.Presence(missing != 0, missing != 1, missing != 2, missing != 3,
			missing != 4, missing != 5, missing != 6, missing != 7, missing != 8, missing != 9,
			missing != 10, missing != 11, distance);
	}
	@Test void everyLiveSceneGateFailsClosedAndReachMustBeFinite() {
		assertTrue(presence(-1, 16).eligible(true));
		assertTrue(presence(-1, 0).eligible(true));
		for (int missing = 0; missing < 12; missing++) assertFalse(presence(missing, 1).eligible(true));
		assertTrue(presence(11, 1).eligible(false)); // Opening may close a real menu, actions may not.
		for (double distance : new double[] {-1, 16.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertFalse(presence(-1, distance).eligible(true));
		}
	}

	@Test void identityGateRejectsEqualLookingReloadedDataAndRemovedThenRegisteredMemberWithoutWriting() {
		var data = home();
		var settlement = data.all().getFirst();
		Object originalMember = data.memberIdentity(RESIDENT);
		var sources = List.of(new Source(Level.OVERWORLD, data));
		var before = data.save(new CompoundTag(), null).copy();
		data.setDirty(false);
		var resolution = resolve(sources, Level.OVERWORLD, RESIDENT);
		assertTrue(ResidentDialogService.matchesClaim(resolution, Level.OVERWORLD, data, settlement, originalMember));
		assertFalse(ResidentDialogService.matchesClaim(resolution, Level.NETHER, data, settlement, originalMember));
		assertFalse(ResidentDialogService.matchesClaim(resolution, Level.OVERWORLD, data, settlement, new Object()));
		assertFalse(ResidentDialogService.matchesClaim(resolution, Level.OVERWORLD, home(), settlement, originalMember));
		var equalSettlement = new Settlement(settlement.id(), settlement.center(), settlement.name(), settlement.foundedDay());
		assertEquals(settlement, equalSettlement);
		assertFalse(ResidentDialogService.matchesClaim(resolution, Level.OVERWORLD, data, equalSettlement, originalMember));
		assertEquals(before, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
		data.removeMember(RESIDENT);
		assertFalse(ResidentDialogService.matchesClaim(resolve(sources, Level.OVERWORLD, RESIDENT), Level.OVERWORLD, data, settlement, originalMember));
		data.registerMember(settlement, RESIDENT, 0);
		assertFalse(ResidentDialogService.matchesClaim(resolve(sources, Level.OVERWORLD, RESIDENT), Level.OVERWORLD, data, settlement, originalMember));
	}

	@Test void conflictsUnavailableAndRemoteClaimsCannotBecomeActionable() {
		var data = home();
		var settlement = data.all().getFirst();
		Object member = data.memberIdentity(RESIDENT);
		var conflict = resolve(List.of(new Source(Level.OVERWORLD, data), new Source(Level.NETHER, home())), Level.OVERWORLD, RESIDENT);
		assertFalse(ResidentDialogService.matchesClaim(conflict, Level.OVERWORLD, data, settlement, member));
		var unavailable = resolve((List<Source>) null, Level.OVERWORLD, RESIDENT);
		assertFalse(ResidentDialogService.matchesClaim(unavailable, Level.OVERWORLD, data, settlement, member));
		assertTrue(VillagerInteraction.blocksVanillaTrade(conflict));
		assertTrue(VillagerInteraction.blocksVanillaTrade(unavailable));
	}

	@Test void giftAvailabilityUsesCurrentFoodTypeWithoutMutatingItemsOrData() {
		ItemStack current = new ItemStack(Items.BREAD, 3);
		assertTrue(ResidentDialogService.giftAvailable(current, false));
		assertEquals(3, current.getCount());
		current.set(DataComponents.CUSTOM_NAME, Component.literal("Special bread"));
		assertFalse(ResidentDialogService.giftAvailable(current, false));
		current = new ItemStack(Items.STONE, 2);
		assertFalse(ResidentDialogService.giftAvailable(current, false));
		assertEquals(2, current.getCount());
		assertFalse(ResidentDialogService.giftAvailable(ItemStack.EMPTY, true));
	}

	@Test void sequentialRequestsUseNewSelectedHandAndDuplicateGiftsConserveResources() {
		for (boolean survival : List.of(false, true)) {
			var data = home();
			var settlement = data.all().getFirst();
			data.setSatiety(settlement, RESIDENT, 80);
			var state = data.getOrCreateSimulation(settlement);
			state.mapResident(RESIDENT, false, 80);
			state.setSurvivalEnabled(survival, 0);
			var hand = new SimpleContainer(3);
			var stock = new SimpleContainer(3);
			hand.setItem(0, new ItemStack(Items.SWEET_BERRIES, 4));
			hand.setItem(1, new ItemStack(Items.SWEET_BERRIES, 4));
			var selected = new AtomicInteger(0);
			var session = new ResidentDialogSession<>(data, 0);
			assertEquals(ResidentDialogSession.Request.NEXT, session.claim(1, 1));
			var first = VillagerGiftService.offer(data, settlement, RESIDENT, false, "minecraft-player:dialog", "Player",
				hand, selected.get(), selected::get, hand.getItem(selected.get()).copy(), stock);
			assertTrue(first.accepted());
			assertEquals(1, first.moved());
			assertEquals(3, hand.getItem(0).getCount());
			selected.set(1); // A second click must use this live hand, not OPEN's original selection.
			var afterGift = data.save(new CompoundTag(), null).copy();
			data.setDirty(false);
			assertEquals(ResidentDialogSession.Request.DUPLICATE, session.claim(1, 2));
			assertEquals(afterGift, data.save(new CompoundTag(), null));
			assertFalse(data.isDirty());
			assertEquals(4, hand.getItem(1).getCount());
			assertEquals(ResidentDialogSession.Request.NEXT, session.claim(2, 3));
			var second = VillagerGiftService.offer(data, settlement, RESIDENT, false, "minecraft-player:dialog", "Player",
				hand, selected.get(), selected::get, hand.getItem(selected.get()).copy(), stock);
			assertTrue(second.accepted());
			assertEquals(1, second.moved());
			assertEquals(3, hand.getItem(0).getCount());
			assertEquals(3, hand.getItem(1).getCount());
			assertEquals(survival ? 36 : 2, SettlementReserves.inspect(data, settlement, stock).current().orElseThrow());
			assertEquals(80, data.needsOf(settlement, RESIDENT).satiety());
			assertEquals(20, state.world().person(state.residentId(RESIDENT).orElseThrow()).hunger());
			state.world().assertConservation();
			session.close();
			assertEquals(ResidentDialogSession.Request.CLOSED, session.claim(3, 4));
		}
	}

	private static SettlementSavedData home() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Dialog test", 0);
		data.registerMember(settlement, RESIDENT, 0);
		return data;
	}
}
