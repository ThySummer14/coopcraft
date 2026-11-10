package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production finder/harvest boundaries with lazy world adapters, without starting a server. */
class SettlementAuthorizedForagingTest {
	private static final UUID ACTOR = new UUID(60, 1), OPERATOR = new UUID(60, 2);
	private static final BlockPos TARGET = new BlockPos(2, 64, 0), ORIGIN = new BlockPos(0, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static BlockState berry(int age) { return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, age); }

	private static final class Fixture {
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(ORIGIN, "Home", 0);
		final SimpleContainer bag;
		BlockState block = berry(3);
		boolean adult = true;
		int reads, loot, writes;
		Runnable afterLoot = () -> {};
		Fixture(boolean permit) { this(permit, new SimpleContainer(2)); }
		Fixture(boolean permit, SimpleContainer bag) {
			this.bag = bag;
			data.registerMember(camp, ACTOR, 0);
			if (permit) permit();
		}
		void permit() { data.permitForagingArea(camp, OPERATOR, ORIGIN, 4); }
		int harvest() {
			return SettlementForaging.harvest(data, camp, ACTOR, TARGET, bag, () -> adult, () -> true, () -> true,
				() -> { reads++; return block; }, () -> { loot++; afterLoot.run(); return List.of(new ItemStack(Items.SWEET_BERRIES, 3)); },
				state -> { writes++; block = state.setValue(SweetBerryBushBlock.AGE, 1); return true; });
		}
		void noHarvest() {
			assertEquals(0, harvest()); assertEquals(0, writes); assertTrue(bag.isEmpty());
		}
	}

	@Test void noPermitOrNonmemberNeverReachesWorldReadsEvenInOrdinaryMode() {
		for (boolean missingPermit : List.of(false, true)) {
			Fixture f = new Fixture(!missingPermit);
			if (!missingPermit) f.data.removeMember(ACTOR);
			f.noHarvest(); assertEquals(0, f.reads); assertEquals(0, f.loot);
			Predicate<BlockPos> forbidden = pos -> { fail("Unauthorized scan reached world predicates"); return true; };
			assertTrue(SettlementForaging.findRipeBush(f.data, f.camp, ACTOR, ORIGIN, () -> true,
				forbidden, forbidden, forbidden, forbidden, pos -> berry(3)).isEmpty());
		}
	}

	@Test void anotherCampsPermitAndCopiedSettlementNeverAuthorizeThisResident() {
		Fixture f = new Fixture(false);
		Settlement other = f.data.getOrCreate(new BlockPos(50, 64, 0), "Other", 0);
		assertNotEquals(f.camp.id(), other.id());
		assertEquals(SettlementForagingAreas.Status.CREATED, f.data.permitForagingArea(other, OPERATOR, ORIGIN, 4).status());
		f.noHarvest(); assertEquals(0, f.reads);
		f.permit();
		Settlement copy = new Settlement(f.camp.id(), f.camp.center(), f.camp.name(), f.camp.foundedDay());
		assertFalse(SettlementForaging.validTarget(f.data, copy, ACTOR, TARGET, () -> true,
			() -> { fail("Copied camp reached world reads"); return true; }, () -> true, () -> berry(3)));
	}

	@Test void liveScanReadsOnlyOwnPermitAndEveryWorldGatePrecedesTheBlockRead() {
		Fixture f = new Fixture(true);
		List<BlockPos> bounds = new ArrayList<>(), loaded = new ArrayList<>(), ticking = new ArrayList<>(), available = new ArrayList<>();
		Function<BlockPos, BlockState> blocks = pos -> {
			assertTrue(f.data.canHarvestBerries(f.camp, pos));
			assertTrue(bounds.contains(pos)); assertTrue(loaded.contains(pos));
			assertTrue(ticking.contains(pos)); assertTrue(available.contains(pos));
			assertEquals(64, pos.getY()); assertEquals(2, pos.getX()); assertEquals(0, pos.getZ());
			return berry(3);
		};
		assertEquals(TARGET, SettlementForaging.findRipeBush(f.data, f.camp, ACTOR, ORIGIN, () -> true,
			pos -> { bounds.add(pos); return pos.getY() == 64; },
			pos -> { loaded.add(pos); return pos.getX() == 2; },
			pos -> { ticking.add(pos); return pos.getZ() == 0; },
			pos -> { available.add(pos); return true; }, blocks).orElseThrow());
		assertTrue(bounds.stream().allMatch(pos -> f.data.foragingAreaOf(f.camp).orElseThrow().contains(pos)));
	}

	@Test void protectionWinsBeforeReadsAndRemovingItDoesNotInventAPermit() {
		Fixture f = new Fixture(true);
		var protection = f.data.addProtectedArea(OPERATOR, "House", -4, -4, 4, 4).area();
		f.noHarvest(); assertEquals(0, f.reads); assertEquals(0, f.loot);
		assertTrue(SettlementForaging.findRipeBush(f.data, f.camp, ACTOR, ORIGIN, () -> true,
			pos -> { fail("Protected position reached world reads"); return true; }, pos -> true,
			pos -> true, pos -> true, pos -> berry(3)).isEmpty());
		f.data.clearForagingArea(f.camp);
		assertEquals(PlayerProtectedAreas.Status.REMOVED, f.data.removeProtectedArea(protection.id(), OPERATOR, false));
		f.noHarvest(); assertEquals(0, f.reads);
	}

	@Test void replacingOrRevokingAndRegrantingPermitDuringLootRejectsOldAttempt() {
		for (int mutation = 0; mutation < 4; mutation++) {
			Fixture f = new Fixture(true); int change = mutation;
			f.afterLoot = () -> {
				switch (change) {
					case 0 -> f.data.clearForagingArea(f.camp);
					case 1 -> f.data.permitForagingArea(f.camp, OPERATOR, ORIGIN, 5);
					case 2 -> { f.data.clearForagingArea(f.camp); f.permit(); }
					case 3 -> f.data.addProtectedArea(OPERATOR, "New house", 2, 0, 2, 0);
				}
			};
			f.noHarvest(); assertEquals(1, f.loot); assertEquals(berry(3), f.block);
		}
	}

	@Test void scanCannotReturnAnEarlierCandidateAfterItsPermitChanged() {
		Fixture f = new Fixture(true);
		boolean[] changed = {false};
		assertTrue(SettlementForaging.findRipeBush(f.data, f.camp, ACTOR, ORIGIN, () -> true,
			pos -> true, pos -> true, pos -> true, pos -> true, pos -> {
				if (!changed[0]) { changed[0] = true; f.data.clearForagingArea(f.camp); f.permit(); }
				return berry(3);
			}).isEmpty());
	}

	@Test void changedBlockOrMaturityAfterLootCannotRegrowOrProduceBerries() {
		for (BlockState replacement : List.of(berry(1), berry(2), Blocks.STONE.defaultBlockState(), Blocks.POTATOES.defaultBlockState())) {
			Fixture f = new Fixture(true); f.afterLoot = () -> f.block = replacement;
			f.noHarvest(); assertEquals(replacement, f.block);
		}
	}

	@Test void inventoryPlanningRechecksPermissionWithoutDestroyingExistingCargo() {
		Fixture[] holder = new Fixture[1];
		boolean[] mutate = {false};
		SimpleContainer bag = new SimpleContainer(1) {
			@Override public ItemStack getItem(int slot) {
				if (mutate[0]) { mutate[0] = false; holder[0].data.clearForagingArea(holder[0].camp); holder[0].permit(); }
				return super.getItem(slot);
			}
		};
		Fixture f = holder[0] = new Fixture(true, bag);
		bag.setItem(0, new ItemStack(Items.SWEET_BERRIES, 7));
		f.afterLoot = () -> mutate[0] = true;
		assertEquals(0, f.harvest()); assertEquals(0, f.writes); assertEquals(7, bag.countItem(Items.SWEET_BERRIES));
		assertEquals(berry(3), f.block);
		assertTrue(SettlementForaging.canForage(f.data, f.camp), "Shared potato policy must not require berry permission");
	}
}
