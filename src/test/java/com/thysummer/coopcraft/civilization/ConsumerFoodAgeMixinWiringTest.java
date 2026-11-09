package com.thysummer.coopcraft.civilization;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.google.gson.JsonParser;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parses actual hook bodies and registrations; does not claim runtime Mixin application or gameplay. */
class ConsumerFoodAgeMixinWiringTest {
	private static final String STACK_GUARD = "if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(stack);";
	private static final String SELF_STACK = "ItemStack stack = (ItemStack) (Object) this;";
	private static final String EAT_TWO = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;";
	private static final String EAT_THREE = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;";
	private static final List<String> MIXINS = List.of("ItemStackFoodAgeMixin", "LivingEntityFoodAgeMixin",
		"PlayerFoodAgeMixin", "MobFoodAgeMixin", "RecipeManagerFoodAgeMixin");

	private record Hook(String parameters, String body) { }

	@Test
	void itemStackEntryFinishAndPlantingGuardsKeepTheOriginalStackAndNeverReadAClock() throws Exception {
		assertHooks("ItemStackFoodAgeMixin", "ItemStack", Map.of(
			"use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
			new Hook("Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir",
				SELF_STACK + "if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(InteractionResultHolder.fail(stack));"),
			"finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
			new Hook("Level level, LivingEntity entity, CallbackInfoReturnable<ItemStack> cir", SELF_STACK + STACK_GUARD),
			"useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
			new Hook("UseOnContext context, CallbackInfoReturnable<InteractionResult> cir",
				SELF_STACK + "if (SettlementFoodAge.isMarked(stack)) cir.setReturnValue(InteractionResult.FAIL);")));
	}

	@Test
	void bothLivingEntityOverloadsReturnBeforeAnyFoodEffectsOrConsumption() throws Exception {
		assertHooks("LivingEntityFoodAgeMixin", "LivingEntity", Map.of(
			EAT_TWO, new Hook("Level level, ItemStack stack, CallbackInfoReturnable<ItemStack> cir", STACK_GUARD),
			EAT_THREE, new Hook("Level level, ItemStack stack, FoodProperties food, CallbackInfoReturnable<ItemStack> cir", STACK_GUARD)));
	}

	@Test
	void playerOverrideIsGuardedBeforeNutritionStatisticsAndRemainderItems() throws Exception {
		assertHooks("PlayerFoodAgeMixin", "Player", Map.of(EAT_THREE,
			new Hook("Level level, ItemStack stack, FoodProperties food, CallbackInfoReturnable<ItemStack> cir", STACK_GUARD)));
	}

	@Test
	void mobGuardUsesTheInteractingHandAtTheFinalEntryBeforeMobInteract() throws Exception {
		assertHooks("MobFoodAgeMixin", "Mob", Map.of(
			"interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
			new Hook("Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir",
				"if (SettlementFoodAge.isMarked(player.getItemInHand(hand))) cir.setReturnValue(InteractionResult.FAIL);")));
	}

	@Test
	void everyRecipeLookupRejectsMarkedInputBeforeCraftingOrAcceptingACachedHolder() throws Exception {
		String emptyRecipe = "if (SettlementVanillaFoodGuard.containsMarked(input)) cir.setReturnValue(Optional.empty());";
		String recipeCallback = "CallbackInfoReturnable<Optional<RecipeHolder<T>>> cir";
		assertHooks("RecipeManagerFoodAgeMixin", "RecipeManager", Map.of(
			"getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
			new Hook("RecipeType<T> type, I input, Level level," + recipeCallback, emptyRecipe),
			"getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
			new Hook("RecipeType<T> type, I input, Level level, ResourceLocation id," + recipeCallback, emptyRecipe),
			"getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
			new Hook("RecipeType<T> type, I input, Level level, RecipeHolder<T> cached," + recipeCallback, emptyRecipe),
			"getRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;",
			new Hook("RecipeType<T> type, I input, Level level, CallbackInfoReturnable<List<RecipeHolder<T>>> cir",
				"if (SettlementVanillaFoodGuard.containsMarked(input)) cir.setReturnValue(List.of());")));
	}

	@Test
	void allConsumerGuardsAreRequiredAndRegisteredExactlyOnceOnBothSides() throws Exception {
		try (var input = getClass().getResourceAsStream("/coopcraft.mixins.json")) {
			assertNotNull(input);
			var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			assertTrue(json.get("required").getAsBoolean());
			assertEquals("com.thysummer.coopcraft.mixin", json.get("package").getAsString());
			assertEquals(1, json.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
			List<String> common = new ArrayList<>();
			json.getAsJsonArray("mixins").forEach(value -> common.add(value.getAsString()));
			for (String mixin : MIXINS) assertEquals(1, Collections.frequency(common, mixin), mixin);
			for (String side : List.of("client", "server")) {
				if (json.has(side)) json.getAsJsonArray(side).forEach(value -> assertFalse(MIXINS.contains(value.getAsString())));
			}
		}
	}

	private static void assertHooks(String mixin, String target, Map<String, Hook> expected) throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "The project test suite requires its configured JDK, not a JRE");
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		Path source = Path.of("src/main/java/com/thysummer/coopcraft/mixin/" + mixin + ".java");
		try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
				List.of("-proc:none"), null, files.getJavaFileObjects(source));
			var unit = task.parse().iterator().next();
			assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
				diagnostics.getDiagnostics().toString());
			assertEquals("com.thysummer.coopcraft.mixin", unit.getPackageName().toString());
			ClassTree type = assertInstanceOf(ClassTree.class, unit.getTypeDecls().getFirst());
			assertEquals(mixin, type.getSimpleName().toString());
			assertEquals(List.of("@Mixin(" + target + ".class)"),
				type.getModifiers().getAnnotations().stream().map(a -> compact(a.toString())).toList());
			Map<String, Hook> actual = new LinkedHashMap<>();
			for (var member : type.getMembers()) {
				MethodTree method = assertInstanceOf(MethodTree.class, member,
					"These guards must remain stateless: " + mixin);
				assertEquals("void", method.getReturnType().toString());
				assertTrue(method.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PRIVATE));
				assertEquals(1, method.getModifiers().getAnnotations().size());
				AnnotationTree annotation = method.getModifiers().getAnnotations().getFirst();
				assertEquals("Inject", annotation.getAnnotationType().toString());
				Map<String, String> options = new LinkedHashMap<>();
				String descriptor = null;
				for (var argument : annotation.getArguments()) {
					AssignmentTree option = assertInstanceOf(AssignmentTree.class, argument);
					String name = option.getVariable().toString();
					assertNull(options.put(name, compact(option.getExpression().toString())));
					if (name.equals("method")) descriptor = (String) assertInstanceOf(LiteralTree.class, option.getExpression()).getValue();
				}
				assertNotNull(descriptor);
				assertEquals(Map.of("method", '"' + descriptor + '"', "at", "@At(\"HEAD\")", "cancellable", "true"), options);
				Hook wanted = expected.get(descriptor);
				assertNotNull(wanted, "Unexpected consumer target: " + descriptor);
				String parameters = String.join(",", method.getParameters().stream().map(Object::toString).toList());
				assertEquals(compact(wanted.parameters()), compact(parameters), descriptor);
				assertEquals(compact("{" + wanted.body() + "}"), compact(method.getBody().toString()), descriptor);
				assertNull(actual.put(descriptor, wanted), "Duplicate hook: " + descriptor);
			}
			assertEquals(expected, actual);
		}
	}

	private static String compact(String source) {
		return source.replaceAll("\\s+", "");
	}
}
