package com.thysummer.coopcraft.civilization;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class SettlementShearsEquipmentTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static ItemStack unknown(Item item) {
		ItemStack stack = new ItemStack(item);
		CompoundTag tag = new CompoundTag(); tag.putString(SettlementShearsLoan.KEY, "future");
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}
	@Test void displayWritesCannotClearOrReplaceEvenUnknownBorrowedTools() {
		Object actor = new Object();
		for (Item item : List.of(Items.SHEARS, Items.STONE_PICKAXE, Items.BREAD)) {
			ItemStack held = unknown(item), before = held.copy();
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, ItemStack.EMPTY));
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, new ItemStack(Items.BONE_MEAL)));
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, held.copy()));
			assertFalse(SettlementShearsEquipment.blocksMainHandChange(actor, held, held));
			assertTrue(ItemStack.matches(before, held));
		}
	}
	@Test void unmarkedPersonalEquipmentAndEmptyHandKeepVanillaBehavior() {
		for (ItemStack held : List.of(ItemStack.EMPTY, new ItemStack(Items.SHEARS), new ItemStack(Items.STONE_PICKAXE))) {
			assertFalse(SettlementShearsEquipment.blocksMainHandChange(new Object(), held, ItemStack.EMPTY));
			assertFalse(SettlementShearsEquipment.blocksMainHandChange(new Object(), held, new ItemStack(Items.BONE_MEAL)));
		}
	}
	@Test void exactRemovalPermitAllowsOnlyOneEmptyTransitionForOneActorAndStack() {
		Object actor = new Object(); ItemStack held = unknown(Items.SHEARS);
		SettlementShearsEquipment.withRemoval(actor, held, () -> {
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(new Object(), held, ItemStack.EMPTY));
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held.copy(), ItemStack.EMPTY));
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, new ItemStack(Items.SHEARS)));
			assertFalse(SettlementShearsEquipment.blocksMainHandChange(actor, held, ItemStack.EMPTY));
			assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, ItemStack.EMPTY));
		});
		assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, ItemStack.EMPTY));
	}
	@Test void exceptionsAndNestedScopesDoNotLeakPermission() {
		Object actor = new Object(); ItemStack held = unknown(Items.SHEARS);
		assertThrows(IllegalArgumentException.class, () -> SettlementShearsEquipment.withRemoval(actor, held, () -> {
			assertThrows(IllegalStateException.class, () -> SettlementShearsEquipment.withRemoval(actor, held, () -> fail("nested")));
			throw new IllegalArgumentException("callback failed");
		}));
		assertTrue(SettlementShearsEquipment.blocksMainHandChange(actor, held, ItemStack.EMPTY));
		SettlementShearsEquipment.withRemoval(actor, held,
			() -> assertFalse(SettlementShearsEquipment.blocksMainHandChange(actor, held, ItemStack.EMPTY)));
	}
	@Test void purposeMarkersCannotBecomeOrdinaryFoodMaterialsOrPolishedPersonalTools() {
		for (Item item : List.of(Items.OAK_LOG, Items.COBBLESTONE, Items.STICK, Items.SMOOTH_STONE, Items.OAK_PLANKS, Items.BREAD)) {
			ItemStack held = unknown(item), before = held.copy();
			assertFalse(SettlementCargo.isMaterial(held));
			assertEquals(0, ConstructionMaterials.realAvailable(new SimpleContainer(held), item));
			assertEquals(0, SettlementFood.points(held));
			assertFalse(SettlementFoodCargo.isFood(held));
			SimpleContainer source = new SimpleContainer(held), target = new SimpleContainer(1);
			assertEquals(0, SettlementStockpile.transferItem(source, target, item, 1));
			assertEquals(0, VillagerWorkGoal.countCraftingItems(source, item));
			assertEquals(0, VillagerWorkGoal.transferCraftingItems(source, target, item, 1));
			assertEquals(0, VillagerWorkGoal.removeItems(source, item, 1));
			assertTrue(ItemStack.matches(before, source.getItem(0)));
		}
		assertFalse(SettlementToolUpgrade.supportsPolishing(unknown(Items.STONE_PICKAXE)));
	}
	@Test void actualMinecraftMobExposesTheGuardedMainHandSetterAndMixinIsRequired() throws Exception {
		AtomicInteger methods = new AtomicInteger();
		String descriptor = "(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V";
		try (InputStream input = Mob.class.getResourceAsStream("Mob.class")) {
			assertNotNull(input);
			new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
					if (name.equals("setItemSlot") && desc.equals(descriptor)) methods.incrementAndGet();
					return null;
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		assertEquals(1, methods.get());
		String mixin = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/mixin/MobShearsEquipmentMixin.java"));
		assertTrue(mixin.contains("setItemSlot" + descriptor));
		assertTrue(mixin.contains("@At(\"HEAD\")")); assertTrue(mixin.contains("cancellable = true, require = 1, allow = 1"));
		assertTrue(mixin.contains("instanceof Villager")); assertTrue(mixin.contains("slot == EquipmentSlot.MAINHAND"));
		assertTrue(mixin.contains("!villager.level().isClientSide"), "Client equipment packets must not need a server custody permit");
		assertTrue(Files.readString(Path.of("src/main/resources/coopcraft.mixins.json")).contains("\"MobShearsEquipmentMixin\""));
	}
}
