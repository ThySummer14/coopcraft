package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("BlueprintManager has a static reload snapshot")
class BlueprintManagerTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void invalidReloadPreservesEntirePreviousSnapshotAndItsPlanObjects() throws Exception {
		Field field = BlueprintManager.class.getDeclaredField("plans");
		field.setAccessible(true);
		Object previous = field.get(null);
		try {
			BlueprintManager manager = new BlueprintManager();
			ResourceLocation first = ResourceLocation.parse("coopcraft:first_fixture");
			ResourceLocation second = ResourceLocation.parse("coopcraft:second_fixture");
			ResourceLocation added = ResourceLocation.parse("coopcraft:added_fixture");
			ResourceLocation invalid = ResourceLocation.parse("coopcraft:invalid_fixture");
			manager.apply(Map.of(first, valid("stone"), second, valid("dirt")), null, null);
			Object snapshot = field.get(null);
			ConstructionPlan firstPlan = BlueprintManager.get(first);
			ConstructionPlan secondPlan = BlueprintManager.get(second);
			assertNotNull(firstPlan);
			assertNotNull(secondPlan);

			Map<ResourceLocation, JsonElement> replacement = new LinkedHashMap<>();
			replacement.put(first, valid("cobblestone"));
			replacement.put(added, valid("oak_planks"));
			replacement.put(invalid, JsonParser.parseString("{}"));
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> manager.apply(replacement, null, null));
			assertTrue(error.getMessage().contains(invalid.toString()), error::getMessage);
			assertSame(snapshot, field.get(null));
			assertEquals(Map.of(first, firstPlan, second, secondPlan), field.get(null));
			assertSame(firstPlan, BlueprintManager.get(first));
			assertSame(secondPlan, BlueprintManager.get(second));
			assertNull(BlueprintManager.get(added));
			assertNull(BlueprintManager.get(invalid));
		} finally {
			field.set(null, previous);
		}
	}

	private static JsonElement valid(String block) {
		return JsonParser.parseString("{\"special\":[{\"pos\":[0,0,0],\"block\":\"" + block + "\"}]}");
	}
}
