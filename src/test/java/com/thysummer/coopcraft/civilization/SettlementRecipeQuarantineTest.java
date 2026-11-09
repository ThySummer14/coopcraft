package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.mixin.RecipeManagerFoodAgeMixin;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.junit.jupiter.api.Assertions.*;

/** Calls the guard handlers only. No mixin transformation, recipe execution or game is started. */
class SettlementRecipeQuarantineTest {
	private static final RecipeManagerFoodAgeMixin GUARD = new RecipeManagerFoodAgeMixin() { };
	private static final UUID CLOCK = new UUID(431, 1);

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void everyLookupIncludingCachedRecipeIsCancelledForMarkedInputs() throws Exception {
		for (var item : List.of(Items.SWEET_BERRIES, Items.POTATO, Items.COD, Items.BEEF)) {
			ItemStack stack = SettlementFoodAge.stamp(new ItemStack(item, 9), CLOCK, 0);
			var patch = stack.getComponentsPatch();
			checkAllHandlers(new SingleRecipeInput(stack), true);
			assertEquals(9, stack.getCount());
			assertEquals(patch, stack.getComponentsPatch());
		}
	}

	@Test void ordinaryAndUnrelatedCustomDataInputsArePassedThrough() throws Exception {
		ItemStack ordinary = new ItemStack(Items.POTATO, 7);
		ItemStack customized = ordinary.copy();
		CompoundTag other = new CompoundTag(); other.putString("OtherMod", "preserve");
		customized.set(DataComponents.CUSTOM_DATA, CustomData.of(other));
		for (ItemStack stack : List.of(ordinary, customized, ItemStack.EMPTY)) {
			checkAllHandlers(new SingleRecipeInput(stack), false);
		}
		assertEquals(7, ordinary.getCount());
		assertEquals(7, customized.getCount());
		assertEquals(other, customized.get(DataComponents.CUSTOM_DATA).copyTag());
	}

	@Test void unknownOrMalformedMarkerCannotFallThroughToRecipes() throws Exception {
		for (int format : List.of(0, 1, 999)) {
			CompoundTag age = new CompoundTag(); age.putInt("Format", format);
			CompoundTag data = new CompoundTag(); data.put("coopcraft_food_age", age);
			ItemStack stack = new ItemStack(Items.POTATO, 3);
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
			assertTrue(SettlementFoodAge.read(stack).isEmpty());
			checkAllHandlers(new SingleRecipeInput(stack), true);
			assertEquals(3, stack.getCount());
			assertEquals(data, stack.get(DataComponents.CUSTOM_DATA).copyTag());
		}
	}

	@Test void mixedCraftingInputIsBlockedWithoutChangingAnyIngredient() throws Exception {
		ItemStack plain = new ItemStack(Items.POTATO, 4);
		ItemStack marked = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 2), CLOCK, 7);
		var stamp = SettlementFoodAge.read(marked);
		checkAllHandlers(CraftingInput.of(2, 2, List.of(plain, ItemStack.EMPTY, marked, ItemStack.EMPTY)), true);
		assertEquals(4, plain.getCount());
		assertEquals(2, marked.getCount());
		assertEquals(stamp, SettlementFoodAge.read(marked));
	}

	private static void checkAllHandlers(RecipeInput input, boolean blocked) throws Exception {
		int checked = 0;
		for (Method method : RecipeManagerFoodAgeMixin.class.getDeclaredMethods()) {
			if (!method.getName().startsWith("coopcraft$guard")) continue;
			Object sentinel = new Object();
			CallbackInfoReturnable<Object> callback = new CallbackInfoReturnable<>("guard-test", true, sentinel);
			Object[] args = new Object[method.getParameterCount()];
			args[1] = input;
			args[args.length - 1] = callback;
			method.setAccessible(true);
			method.invoke(GUARD, args);
			assertEquals(blocked, callback.isCancelled(), method.getName());
			if (blocked) {
				assertEquals(method.getName().endsWith("List") ? List.of() : Optional.empty(), callback.getReturnValue());
			} else {
				assertSame(sentinel, callback.getReturnValue());
			}
			checked++;
		}
		assertEquals(4, checked);
	}
}
