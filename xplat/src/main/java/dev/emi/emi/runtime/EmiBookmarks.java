package dev.emi.emi.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

public class EmiBookmarks {
	private static final List<EmiIngredient> FALLBACK = List.of();
	private static final Map<Identifier, Long> BATCHES = Maps.newHashMap();
	private static final Map<String, List<EmiIngredient>> LAYOUT_CACHE = Maps.newHashMap();
	private static int version = 0;

	private static void bump() {
		version++;
		LAYOUT_CACHE.clear();
	}

	public static List<? extends EmiIngredient> getSidebar(EmiScreenManager.ScreenSpace space) {
		if (space == null || space.pageSize <= 0 || space.widths.length == 0) {
			return FALLBACK;
		}
		String key = layoutKey(space);
		return LAYOUT_CACHE.computeIfAbsent(key, ignored -> buildLayout(space));
	}

	public static List<? extends EmiIngredient> getFallbackSidebar() {
		return FALLBACK;
	}

	private static String layoutKey(EmiScreenManager.ScreenSpace space) {
		StringBuilder sb = new StringBuilder();
		sb.append(version).append('|').append(space.pageSize).append('|').append(space.tw).append('|').append(space.th);
		for (int w : space.widths) {
			sb.append('|').append(w);
		}
		return sb.toString();
	}

	private static List<EmiIngredient> buildLayout(EmiScreenManager.ScreenSpace space) {
		List<EmiIngredient> list = Lists.newArrayList();
		List<EmiFavorite> bookmarkRecipes = Lists.newArrayList();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				bookmarkRecipes.add(fav);
			}
		}
		if (bookmarkRecipes.isEmpty()) {
			return list;
		}

		int row = 0;
		int col = 0;
		for (EmiFavorite fav : bookmarkRecipes) {
			EmiRecipe recipe = fav.getRecipe();
			if (recipe == null || recipe.getId() == null) {
				continue;
			}
			int width = widthForRow(space, row);
			if (width <= 0) {
				break;
			}
			if (col != 0) {
				while (col < width) {
					list.add(EmiStack.EMPTY);
					col++;
				}
				col = 0;
				row++;
				width = widthForRow(space, row);
				if (width <= 0) {
					break;
				}
			}

			long batches = Math.max(1, BATCHES.getOrDefault(recipe.getId(), 1L));
			EmiIngredient output = firstOutput(recipe);
			if (output.isEmpty()) {
				continue;
			}
			list.add(new EmiFavorite(scaleAmount(output, batches), recipe));
			col++;

			List<EmiIngredient> inputs = recipe.getInputs();
			int limit = Math.max(0, width - 1);
			for (int i = 0; i < inputs.size() && i < limit; i++) {
				list.add(new EmiFavorite(scaleAmount(inputs.get(i), batches), recipe));
				col++;
			}

			while (col < width) {
				list.add(EmiStack.EMPTY);
				col++;
			}
			col = 0;
			row++;
		}
		return list;
	}

	private static EmiIngredient firstOutput(EmiRecipe recipe) {
		if (recipe.getOutputs().isEmpty()) {
			return EmiStack.EMPTY;
		}
		return recipe.getOutputs().get(0);
	}

	private static EmiIngredient scaleAmount(EmiIngredient ingredient, long batches) {
		if (ingredient == null || ingredient.isEmpty()) {
			return EmiStack.EMPTY;
		}
		EmiIngredient copy = ingredient.copy();
		long amount = Math.max(1, copy.getAmount());
		copy.setAmount(Math.min(Integer.MAX_VALUE, amount * batches));
		return copy;
	}

	private static int widthForRow(EmiScreenManager.ScreenSpace space, int row) {
		if (space.widths.length == 0) {
			return 0;
		}
		return Math.max(0, space.widths[row % space.widths.length]);
	}

	private static long defaultStepFor(EmiRecipe recipe) {
		EmiIngredient output = firstOutput(recipe);
		if (output.isEmpty() || output.getEmiStacks().isEmpty()) {
			return 64;
		}
		ItemStack stack = output.getEmiStacks().get(0).getItemStack();
		if (stack.isEmpty()) {
			return 64;
		}
		return Math.max(1, stack.getMaxCount());
	}

	public static boolean adjustBatch(EmiRecipe recipe, int delta, boolean largeStep) {
		if (recipe == null || recipe.getId() == null || delta == 0) {
			return false;
		}
		long step = largeStep ? defaultStepFor(recipe) : 1;
		long current = Math.max(1, BATCHES.getOrDefault(recipe.getId(), 1L));
		long next = current + step * delta;
		next = Math.max(1, Math.min(Integer.MAX_VALUE, next));
		if (next == current) {
			return false;
		}
		BATCHES.put(recipe.getId(), next);
		bump();
		EmiPersistentData.save();
		return true;
	}

	public static boolean removeRecipe(EmiRecipe recipe) {
		if (recipe == null || recipe.getId() == null) {
			return false;
		}
		Identifier id = recipe.getId();
		boolean removed = EmiFavorites.favorites.removeIf(f -> f.getRecipe() != null && id.equals(f.getRecipe().getId()));
		if (removed) {
			BATCHES.remove(id);
			bump();
			EmiPersistentData.save();
		}
		return removed;
	}

	public static JsonObject save() {
		JsonObject json = new JsonObject();
		for (Map.Entry<Identifier, Long> entry : BATCHES.entrySet()) {
			json.addProperty(entry.getKey().toString(), entry.getValue());
		}
		return json;
	}

	public static void load(JsonObject json) {
		BATCHES.clear();
		Set<Identifier> validIds = Sets.newHashSet();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				validIds.add(fav.getRecipe().getId());
			}
		}
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			Identifier id = Identifier.tryParse(entry.getKey());
			if (id != null && validIds.contains(id) && entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
				long count = Math.max(1, entry.getValue().getAsLong());
				BATCHES.put(id, count);
			}
		}
		bump();
	}
}