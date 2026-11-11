package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

final class HousingRecipes {
	static final Recipe PLANKS = new Recipe("oak_planks", Items.OAK_PLANKS, 4, List.of(new Ingredient(Items.OAK_LOG, 1)));
	static final Recipe DOOR = new Recipe("oak_door", Items.OAK_DOOR, 3, List.of(new Ingredient(Items.OAK_PLANKS, 3)));
	static final Recipe TORCH = new Recipe("torch", Items.TORCH, 4, List.of(new Ingredient(Items.COAL, 1), new Ingredient(Items.STICK, 1)));
	static final Recipe BED = new Recipe("white_bed", Items.WHITE_BED, 1,
		List.of(new Ingredient(Items.WHITE_WOOL, 3), new Ingredient(Items.OAK_PLANKS, 3)));

	private HousingRecipes() {
	}

	record Recipe(String id, Item output, int outputCount, List<Ingredient> ingredients) {
		Recipe {
			ingredients = List.copyOf(ingredients);
		}
	}

	record Ingredient(Item item, int count) {
	}
}
