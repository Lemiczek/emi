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
	private static final Map<Identifier, Integer> PAGES = Maps.newHashMap();
	private static final Map<String, List<EmiIngredient>> LAYOUT_CACHE = Maps.newHashMap();
	private static int currentPage = 0;
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
		pruneInvalidFavorites();
		List<EmiIngredient> list = Lists.newArrayList();
		Map<Integer, List<EmiFavorite>> pagedRecipes = Maps.newHashMap();
		int maxAssignedPage = -1;
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				int page = Math.max(0, PAGES.getOrDefault(fav.getRecipe().getId(), 0));
				pagedRecipes.computeIfAbsent(page, k -> Lists.newArrayList()).add(fav);
				maxAssignedPage = Math.max(maxAssignedPage, page);
			}
		}
		if (maxAssignedPage < 0) {
			return list;
		}

		int totalPages = maxAssignedPage + 2;
		int page = 0;
		while (page < totalPages) {
			List<EmiFavorite> recipes = pagedRecipes.getOrDefault(page, List.of());
			int consumed = appendPage(space, list, recipes);
			if (consumed < recipes.size()) {
				int nextPage = page + 1;
				List<EmiFavorite> overflow = recipes.subList(consumed, recipes.size());
				List<EmiFavorite> next = pagedRecipes.computeIfAbsent(nextPage, k -> Lists.newArrayList());
				next.addAll(0, overflow);
				for (EmiFavorite fav : overflow) {
					EmiRecipe recipe = fav.getRecipe();
					if (recipe != null && recipe.getId() != null) {
						PAGES.put(recipe.getId(), nextPage);
					}
				}
				totalPages = Math.max(totalPages, nextPage + 2);
			}
			page++;
		}
		return list;
	}

	private static int appendPage(EmiScreenManager.ScreenSpace space, List<EmiIngredient> list, List<EmiFavorite> recipes) {
		int row = 0;
		int index = 0;
		while (row < space.th) {
			int width = widthForRow(space, row);
			if (width > 0) {
				if (index < recipes.size()) {
					EmiFavorite fav = recipes.get(index);
					EmiRecipe recipe = fav.getRecipe();
					if (recipe != null && recipe.getId() != null) {
						long batches = Math.max(1, BATCHES.getOrDefault(recipe.getId(), 1L));
						EmiIngredient output = firstOutput(recipe);
						if (!output.isEmpty()) {
							list.add(BookmarkSlot.output(scaleAmount(output, batches), recipe));
							List<CollapsedInput> inputs = collapseInputs(recipe, batches);
							int limit = Math.max(0, width - 1);
							int col = 1;
							for (int i = 0; i < inputs.size() && i < limit; i++) {
								CollapsedInput input = inputs.get(i);
								list.add(BookmarkSlot.input(input.ingredient, recipe, input.sourceIndices));
								col++;
							}
							while (col < width) {
								list.add(EmiStack.EMPTY);
								col++;
							}
							index++;
						} else {
							for (int col = 0; col < width; col++) {
								list.add(EmiStack.EMPTY);
							}
						}
					} else {
						for (int col = 0; col < width; col++) {
							list.add(EmiStack.EMPTY);
						}
						index++;
					}
				} else {
					for (int col = 0; col < width; col++) {
						list.add(EmiStack.EMPTY);
					}
				}
			}
			row++;
		}
		return index;
	}

	private static List<CollapsedInput> collapseInputs(EmiRecipe recipe, long batches) {
		List<CollapsedInput> collapsed = Lists.newArrayList();
		List<EmiIngredient> inputs = recipe.getInputs();
		for (int i = 0; i < inputs.size(); i++) {
			EmiIngredient scaled = scaleAmount(inputs.get(i), batches);
			if (scaled.isEmpty()) {
				continue;
			}
			CollapsedInput found = null;
			for (CollapsedInput entry : collapsed) {
				if (sameDisplayIngredient(entry.ingredient, scaled)) {
					found = entry;
					break;
				}
			}
			if (found == null) {
				collapsed.add(new CollapsedInput(scaled, i));
			} else {
				found.ingredient.setAmount(Math.min(Integer.MAX_VALUE, found.ingredient.getAmount() + scaled.getAmount()));
				found.addSourceIndex(i);
			}
		}
		return collapsed;
	}

	private static boolean sameDisplayIngredient(EmiIngredient a, EmiIngredient b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
			return false;
		}
		EmiIngredient ac = a.copy();
		EmiIngredient bc = b.copy();
		ac.setAmount(1);
		bc.setAmount(1);
		return EmiIngredient.areEqual(ac, bc);
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
			PAGES.remove(id);
			bump();
			EmiPersistentData.save();
		}
		return removed;
	}

	public static int getCurrentPage() {
		return currentPage;
	}

	public static void setCurrentPage(int page) {
		page = Math.max(0, page);
		if (currentPage != page) {
			currentPage = page;
			EmiPersistentData.save();
		}
	}

	public static void loadCurrentPage(int page) {
		currentPage = Math.max(0, page);
	}

	public static void onRecipeBookmarked(EmiRecipe recipe) {
		if (recipe == null || recipe.getId() == null) {
			return;
		}
		Identifier id = recipe.getId();
		if (!PAGES.containsKey(id)) {
			PAGES.put(id, currentPage);
			bump();
			EmiPersistentData.save();
		}
	}

	public static JsonObject saveCounts() {
		pruneInvalidFavorites();
		JsonObject json = new JsonObject();
		for (Map.Entry<Identifier, Long> entry : BATCHES.entrySet()) {
			json.addProperty(entry.getKey().toString(), entry.getValue());
		}
		return json;
	}

	public static void loadCounts(JsonObject json) {
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

	public static JsonObject savePages() {
		pruneInvalidFavorites();
		JsonObject json = new JsonObject();
		for (Map.Entry<Identifier, Integer> entry : PAGES.entrySet()) {
			json.addProperty(entry.getKey().toString(), Math.max(0, entry.getValue()));
		}
		return json;
	}

	public static void loadPages(JsonObject json) {
		PAGES.clear();
		Set<Identifier> validIds = Sets.newHashSet();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				validIds.add(fav.getRecipe().getId());
			}
		}
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			Identifier id = Identifier.tryParse(entry.getKey());
			if (id != null && validIds.contains(id) && entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
				PAGES.put(id, Math.max(0, entry.getValue().getAsInt()));
			}
		}
		bump();
	}

	private static void pruneInvalidFavorites() {
		Set<Identifier> validIds = Sets.newHashSet();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				validIds.add(fav.getRecipe().getId());
			}
		}
		BATCHES.keySet().removeIf(id -> !validIds.contains(id));
		PAGES.keySet().removeIf(id -> !validIds.contains(id));
	}

	public static class BookmarkSlot extends EmiFavorite {
		private final boolean output;
		private final int[] inputIndices;

		private BookmarkSlot(EmiIngredient stack, EmiRecipe recipe, boolean output, int[] inputIndices) {
			super(stack, recipe);
			this.output = output;
			this.inputIndices = inputIndices;
		}

		public static BookmarkSlot output(EmiIngredient stack, EmiRecipe recipe) {
			return new BookmarkSlot(stack, recipe, true, new int[0]);
		}

		public static BookmarkSlot input(EmiIngredient stack, EmiRecipe recipe, int[] inputIndices) {
			return new BookmarkSlot(stack, recipe, false, inputIndices);
		}

		public boolean isOutput() {
			return output;
		}

		public int[] getInputIndices() {
			return inputIndices;
		}
	}

	private static class CollapsedInput {
		private EmiIngredient ingredient;
		private int[] sourceIndices;

		private CollapsedInput(EmiIngredient ingredient, int sourceIndex) {
			this.ingredient = ingredient;
			this.sourceIndices = new int[] {sourceIndex};
		}

		private void addSourceIndex(int sourceIndex) {
			int[] old = sourceIndices;
			sourceIndices = new int[old.length + 1];
			System.arraycopy(old, 0, sourceIndices, 0, old.length);
			sourceIndices[old.length] = sourceIndex;
		}
	}
}