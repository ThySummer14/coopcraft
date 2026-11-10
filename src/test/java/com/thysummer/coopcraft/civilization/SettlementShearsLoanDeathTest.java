package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Detached real stacks/containers and mapped dependency bytecode; no server or GameTest starts. */
class SettlementShearsLoanDeathTest {
	private static final BlockPos PLACE = new BlockPos(4, 64, 9);
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = VanillaRegistries.createLookup();
	}

	private static final class Fixture {
		final UUID actor = UUID.randomUUID();
		final Object deathIdentity = new Object();
		final SettlementSavedData memberships = new SettlementSavedData();
		final Settlement camp = memberships.getOrCreate(PLACE, "Camp", 0);
		final SimpleContainer bag = new SimpleContainer(8), hand = new SimpleContainer(1);
		final SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		Fixture() { memberships.registerMember(camp, actor, 0); }
		SettlementMembershipClaims.Resolution claims() {
			return SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, memberships)),
				Level.OVERWORLD, actor);
		}
		boolean clear(ItemStack expected) {
			if (hand.getItem(0) != expected) return false;
			hand.setItem(0, ItemStack.EMPTY);
			return true;
		}
		int early() {
			return recovery.captureBeforeDeath(deathIdentity, claims(), Level.OVERWORLD, actor, PLACE, "Worker", () -> bag,
				() -> hand.getItem(0), this::clear);
		}
		int capture() {
			return recovery.captureDeath(claims(), Level.OVERWORLD, actor, PLACE, "Worker", () -> bag,
				() -> hand.getItem(0), this::clear);
		}
		ItemStack loan() {
			return SettlementShearsLoan.mark(personalShears(), Level.OVERWORLD.location(), camp.id(), actor, UUID.randomUUID());
		}
	}

	private static ItemStack personalShears() {
		ItemStack tool = new ItemStack(Items.SHEARS);
		tool.setDamageValue(73);
		tool.set(DataComponents.CUSTOM_NAME, Component.literal("Old warehouse shears"));
		tool.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Keep the original history"))));
		tool.set(DataComponents.REPAIR_COST, 7);
		CompoundTag root = new CompoundTag(); root.putString("OtherMod", "must survive");
		tool.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
		tool.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 3);
		tool.enchant(enchantments.getOrThrow(Enchantments.VANISHING_CURSE), 1);
		return tool;
	}

	private static ItemStack cargo(int slot) {
		ItemStack stack = new ItemStack(Items.COBBLESTONE);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Slot " + slot));
		return stack;
	}

	private static CompoundTag save(SettlementCargoRecovery recovery) {
		return recovery.save(new CompoundTag(), registries);
	}

	@Test void eightBackpackSlotsAndMarkedHandAreOneNineSlotRecordWithOneNotification() {
		Fixture f = new Fixture();
		for (int slot = 0; slot < 8; slot++) f.bag.setItem(slot, cargo(slot));
		ItemStack loan = f.loan(), expected = loan.copy();
		f.hand.setItem(0, loan);
		assertEquals(9, f.early());
		assertTrue(f.bag.isEmpty()); assertTrue(f.hand.isEmpty());
		assertEquals(0, f.early()); assertEquals(0, f.capture());
		assertEquals(0, f.recovery.takeDeathNotice(new Object()));
		assertEquals(9, f.recovery.takeDeathNotice(f.deathIdentity));
		assertEquals(0, f.recovery.takeDeathNotice(f.deathIdentity));
		CompoundTag saved = save(f.recovery);
		assertEquals(3, saved.getInt("DataVersion"));
		assertEquals(9, saved.getList("Records", Tag.TAG_COMPOUND).getCompound(0).getList("Stacks", Tag.TAG_COMPOUND).size());
		SettlementCargoRecovery loaded = SettlementCargoRecovery.load(saved, registries);
		assertEquals(saved, save(loaded));
		assertEquals(0, loaded.takeDeathNotice(f.deathIdentity));
		SimpleContainer destination = new SimpleContainer(8);
		assertEquals(8, loaded.recover(f.actor, destination, () -> true).moved());
		assertEquals(1, loaded.inspect(f.actor).orElseThrow().totalCount());
		assertEquals(0, loaded.recover(f.actor, destination, () -> true).moved());
		SimpleContainer toolRecipient = new SimpleContainer(1);
		assertEquals(1, loaded.recover(f.actor, toolRecipient, () -> true).moved());
		assertEquals(expected.save(registries), toolRecipient.getItem(0).save(registries));
		assertTrue(SettlementShearsLoan.isMarked(toolRecipient.getItem(0)));
		assertEquals(0, loaded.recover(f.actor, toolRecipient, () -> true).moved());
		assertEquals(0, f.capture());
	}

	@Test void ordinaryPersonalHandsNeverEnterCargoEvenIfTheirItemIsNormallyMaterial() {
		for (ItemStack personal : List.of(personalShears(), new ItemStack(Items.STONE_PICKAXE), cargo(20))) {
			Fixture f = new Fixture();
			ItemStack before = personal.copy();
			f.hand.setItem(0, personal); f.bag.setItem(0, cargo(0));
			AtomicInteger clears = new AtomicInteger();
			assertEquals(1, f.recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, PLACE, "Worker", () -> f.bag,
				() -> f.hand.getItem(0), expected -> { clears.incrementAndGet(); return f.clear(expected); }));
			assertEquals(0, clears.get());
			assertSame(personal, f.hand.getItem(0)); assertTrue(ItemStack.matches(before, personal));
			assertTrue(f.bag.isEmpty());
		}
	}

	@Test void futureMalformedForeignAndWrongItemMarkersStayOpaqueThroughDeathAndSave() {
		for (int variant = 0; variant < 4; variant++) {
			Fixture f = new Fixture();
			ItemStack marked = variant == 3 ? new ItemStack(Items.IRON_AXE) : f.loan();
			CompoundTag root = marked.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
			if (variant == 0) root.getCompound(SettlementShearsLoan.KEY).putInt("Version", 99);
			if (variant == 1) root.putString(SettlementShearsLoan.KEY, "unknown format");
			if (variant == 2) root.getCompound(SettlementShearsLoan.KEY).putUUID("Settlement", UUID.randomUUID());
			if (variant == 3) root.putInt(SettlementShearsLoan.KEY, 27);
			marked.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
			ItemStack before = marked.copy();
			f.bag.setItem(0, marked.copy()); f.hand.setItem(0, marked);
			assertEquals(2, f.early());
			assertTrue(f.bag.isEmpty()); assertTrue(f.hand.isEmpty());
			SettlementCargoRecovery loaded = SettlementCargoRecovery.load(save(f.recovery), registries);
			SimpleContainer destination = new SimpleContainer(2);
			assertEquals(2, loaded.recover(f.actor, destination, () -> true).moved());
			for (int slot = 0; slot < 2; slot++) assertEquals(before.save(registries), destination.getItem(slot).save(registries));
		}
	}

	@Test void unsuccessfulHandRemovalCannotCreateACopyInRecovery() {
		Fixture f = new Fixture(); ItemStack original = f.loan(); f.hand.setItem(0, original);
		assertEquals(0, f.recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, PLACE, "Worker", () -> f.bag,
			() -> f.hand.getItem(0), expected -> false));
		assertSame(original, f.hand.getItem(0)); assertTrue(f.recovery.inspect(f.actor).isEmpty());
		assertEquals(1, f.capture()); assertTrue(f.hand.isEmpty()); assertEquals(0, f.capture());
	}

	@Test void boundedNotificationReceiptsNeverEvictPhysicalCargoOrPermitDuplicateCapture() {
		SettlementCargoRecovery recovery = new SettlementCargoRecovery();
		List<Fixture> deaths = new ArrayList<>();
		for (int index = 0; index < 129; index++) {
			Fixture f = new Fixture(); deaths.add(f);
			f.bag.setItem(0, cargo(index)); f.hand.setItem(0, f.loan());
			assertEquals(2, recovery.captureBeforeDeath(f.deathIdentity, f.claims(), Level.OVERWORLD, f.actor, PLACE, "Worker",
				() -> f.bag, () -> f.hand.getItem(0), f::clear));
			assertTrue(f.bag.isEmpty()); assertTrue(f.hand.isEmpty());
		}
		int notices = 0;
		for (Fixture f : deaths) {
			notices += recovery.takeDeathNotice(f.deathIdentity);
			assertEquals(0, recovery.takeDeathNotice(f.deathIdentity));
			assertEquals(2, recovery.inspect(f.actor).orElseThrow().totalCount());
			assertEquals(0, recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, PLACE, "Worker", () -> f.bag,
				() -> f.hand.getItem(0), f::clear));
		}
		assertEquals(128 * 2, notices, "Only the bounded notification receipt may be evicted");
		SettlementCargoRecovery loaded = SettlementCargoRecovery.load(save(recovery), registries);
		for (Fixture f : deaths) {
			assertEquals(2, loaded.recover(f.actor, new SimpleContainer(2), () -> true).moved());
			assertEquals(0, loaded.recover(f.actor, new SimpleContainer(2), () -> true).moved());
		}
	}

	@Test void invalidClaimsBlockedDataAndOversizedBackpackNeverTouchTheHand() {
		Fixture f = new Fixture();
		for (var status : SettlementMembershipClaims.Status.values()) {
			if (status == SettlementMembershipClaims.Status.UNIQUE) continue;
			assertEquals(0, f.recovery.captureDeath(new SettlementMembershipClaims.Resolution(status, List.of()),
				Level.OVERWORLD, f.actor, PLACE, "Worker", () -> { fail("Invalid claims must not read the bag"); return null; },
				() -> { fail("Invalid claims must not read the hand"); return ItemStack.EMPTY; }, expected -> { fail("No hand write"); return false; }));
		}
		assertEquals(0, f.recovery.captureDeath(f.claims(), Level.NETHER, f.actor, PLACE, "Worker", () -> {
			fail("Foreign dimension must not read the bag"); return null;
		}, () -> { fail("No hand read"); return ItemStack.EMPTY; }, expected -> false));
		assertEquals(0, f.recovery.captureDeath(f.claims(), Level.OVERWORLD, f.actor, PLACE, "Worker", () -> new SimpleContainer(9),
			() -> { fail("Backpack size limit is still eight"); return ItemStack.EMPTY; }, expected -> false));
		CompoundTag future = save(f.recovery); future.putInt("DataVersion", 99);
		SettlementCargoRecovery blocked = SettlementCargoRecovery.load(future, registries);
		assertEquals(0, blocked.captureBeforeDeath(f.deathIdentity, f.claims(), Level.OVERWORLD, f.actor, PLACE, "Worker",
			() -> { fail("Blocked recovery must not read bag"); return null; },
			() -> { fail("Blocked recovery must not read hand"); return ItemStack.EMPTY; }, expected -> false));
		assertEquals(0, blocked.takeDeathNotice(f.deathIdentity)); assertEquals(future, save(blocked));
	}

	@Test void oldEightSlotSchemaMigratesButOldNineSlotsAndFutureSchemasRemainUnreadable() {
		Fixture f = new Fixture();
		for (int slot = 0; slot < 8; slot++) f.bag.setItem(slot, cargo(slot));
		assertEquals(8, f.capture());
		CompoundTag legacy = save(f.recovery); legacy.putInt("DataVersion", 1);
		legacy.getList("Records", Tag.TAG_COMPOUND).getCompound(0).remove("DeathSettlement");
		SettlementCargoRecovery migrated = SettlementCargoRecovery.load(legacy, registries);
		assertTrue(migrated.available()); assertEquals(8, migrated.inspect(f.actor).orElseThrow().totalCount());
		CompoundTag upgraded = save(migrated);
		assertEquals(3, upgraded.getInt("DataVersion"));
		assertEquals(legacy.get("Records"), upgraded.get("Records"));
		CompoundTag oldNine = legacy.copy();
		ListTag oldStacks = oldNine.getList("Records", Tag.TAG_COMPOUND).getCompound(0).getList("Stacks", Tag.TAG_COMPOUND);
		oldStacks.add(f.loan().save(registries));
		assertUnavailable(oldNine);
		CompoundTag newNine = oldNine.copy(); newNine.putInt("DataVersion", 2);
		assertTrue(SettlementCargoRecovery.load(newNine, registries).available());
		CompoundTag migratedNine = newNine.copy(); migratedNine.putInt("DataVersion", 3);
		assertEquals(migratedNine, save(SettlementCargoRecovery.load(newNine, registries)));
		CompoundTag newTen = newNine.copy();
		newTen.getList("Records", Tag.TAG_COMPOUND).getCompound(0).getList("Stacks", Tag.TAG_COMPOUND).add(f.loan().save(registries));
		assertUnavailable(newTen);
		CompoundTag v3Ten = newTen.copy(); v3Ten.putInt("DataVersion", 3); assertUnavailable(v3Ten);
		CompoundTag future = newNine.copy(); future.putInt("DataVersion", 4); future.putString("FutureField", "preserve");
		assertUnavailable(future);
		CompoundTag wrongType = newNine.copy(); wrongType.putByte("DataVersion", (byte) 2); assertUnavailable(wrongType);
	}

	private static void assertUnavailable(CompoundTag original) {
		SettlementCargoRecovery loaded = SettlementCargoRecovery.load(original, registries);
		assertFalse(loaded.available()); assertEquals(original, save(loaded));
		CompoundTag savedCopy = save(loaded); savedCopy.remove("Records"); assertEquals(original, save(loaded));
	}

	@Test void actualMappedDeathWritesDeadExactlyOnceBeforeKillerConversionLootAndAfterDeathPoint() throws Exception {
		List<String> sequence = new ArrayList<>();
		AtomicInteger methods = new AtomicInteger();
		try (InputStream input = LivingEntity.class.getResourceAsStream("LivingEntity.class")) {
			assertNotNull(input);
			new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					if (!name.equals("die") || !descriptor.equals("(Lnet/minecraft/world/damagesource/DamageSource;)V")) return null;
					methods.incrementAndGet();
					return new MethodVisitor(Opcodes.ASM9) {
						@Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
							if (opcode == Opcodes.PUTFIELD && owner.equals("net/minecraft/world/entity/LivingEntity")
								&& name.equals("dead") && descriptor.equals("Z")) sequence.add("dead");
						}
						@Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
							if (owner.equals("net/minecraft/world/entity/Entity") && name.equals("killedEntity")
								&& descriptor.equals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)Z")) sequence.add("killer");
							if (owner.equals("net/minecraft/world/entity/LivingEntity") && name.equals("dropAllDeathLoot")
								&& descriptor.equals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V")) sequence.add("loot");
							if (name.equals("broadcastEntityEvent") && descriptor.equals("(Lnet/minecraft/world/entity/Entity;B)V")) sequence.add("afterDeathPoint");
						}
					};
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		assertEquals(1, methods.get());
		assertEquals(List.of("dead", "killer", "loot", "afterDeathPoint"), sequence);
	}

	@Test void requiredMixinIsServerVillagerOnlyAndBridgesBeforeLootWithAuthorizedHandClear() throws Exception {
		String mixin = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/mixin/VillagerLoanDeathMixin.java"));
		String config = Files.readString(Path.of("src/main/resources/coopcraft.mixins.json"));
		String recovery = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/SettlementCargoRecovery.java"));
		String registration = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/CoopCraft.java"));
		assertTrue(config.contains("\"VillagerLoanDeathMixin\""));
		assertTrue(mixin.contains("@Mixin(LivingEntity.class)"));
		assertTrue(mixin.contains("die(Lnet/minecraft/world/damagesource/DamageSource;)V"));
		assertTrue(mixin.contains("Lnet/minecraft/world/entity/LivingEntity;dead:Z"));
		assertTrue(mixin.contains("opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER"));
		assertTrue(mixin.contains("require = 1, allow = 1"));
		assertTrue(mixin.contains("instanceof Villager villager") && mixin.contains("instanceof ServerLevel level"));
		assertTrue(mixin.contains("SettlementCargoRecovery.captureBeforeDeath(level, villager)"));
		assertTrue(recovery.contains("SettlementShearsEquipment.clear(villager, stack)"));
		assertFalse(recovery.contains("setDropChance(")); assertFalse(recovery.contains("setItemSlot("));
		assertTrue(registration.indexOf("SettlementCargoRecovery.captureDeath(serverLevel, villager)")
			< registration.indexOf("SettlementSavedData.removeObservedDeadMember(serverLevel, villager.getUUID())"));
	}
}
