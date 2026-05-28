package dev.emi.emi.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.EmiPort;
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
	private static final Map<Identifier, Integer> ORDER = Maps.newHashMap();
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
		Map<Identifier, EmiFavorite> bookmarks = collectBookmarks();
		if (bookmarks.isEmpty()) {
			return list;
		}
		ensureDefaults(bookmarks);

		int maxAssignedPage = -1;
		for (Identifier id : bookmarks.keySet()) {
			maxAssignedPage = Math.max(maxAssignedPage, Math.max(0, PAGES.getOrDefault(id, 0)));
		}
		if (maxAssignedPage < 0) {
			return list;
		}

		int pageCapacity = rowsPerPage(space);
		int totalPages = maxAssignedPage + 2;
		int page = 0;
		while (page < totalPages) {
			List<Identifier> ordered = getOrderedPageIds(page, bookmarks);
			int consumed = Math.min(pageCapacity, ordered.size());
			List<Identifier> visibleIds = ordered.subList(0, consumed);
			List<EmiFavorite> visible = Lists.newArrayList();
			for (Identifier id : visibleIds) {
				EmiFavorite fav = bookmarks.get(id);
				if (fav != null) {
					visible.add(fav);
				}
			}
			Map<Identifier, Long> solvedBatches = solvePage(visible);
			appendPage(space, list, visible, solvedBatches);

			if (consumed < ordered.size()) {
				int nextPage = page + 1;
				int nextOrder = maxOrderForPage(nextPage) + 1;
				for (int i = consumed; i < ordered.size(); i++) {
					Identifier id = ordered.get(i);
					PAGES.put(id, nextPage);
					ORDER.put(id, nextOrder++);
				}
				totalPages = Math.max(totalPages, nextPage + 2);
			}
			page++;
		}
		return list;
	}

	private static Map<Identifier, EmiFavorite> collectBookmarks() {
		Map<Identifier, EmiFavorite> map = Maps.newHashMap();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			EmiRecipe recipe = fav.getRecipe();
			if (recipe != null && recipe.getId() != null && !map.containsKey(recipe.getId())) {
				map.put(recipe.getId(), fav);
			}
		}
		return map;
	}

	private static void ensureDefaults(Map<Identifier, EmiFavorite> bookmarks) {
		for (Identifier id : bookmarks.keySet()) {
			PAGES.putIfAbsent(id, 0);
		}
		Map<Integer, Integer> nextOrder = Maps.newHashMap();
		for (Identifier id : bookmarks.keySet()) {
			if (ORDER.containsKey(id)) {
				continue;
			}
			int page = Math.max(0, PAGES.getOrDefault(id, 0));
			int value = nextOrder.computeIfAbsent(page, thisPage -> maxOrderForPage(thisPage) + 1);
			ORDER.put(id, value);
			nextOrder.put(page, value + 1);
		}
	}

	private static int appendPage(EmiScreenManager.ScreenSpace space, List<EmiIngredient> list, List<EmiFavorite> recipes, Map<Identifier, Long> solvedBatches) {
		int row = 0;
		int index = 0;
		while (row < space.th) {
			int width = widthForRow(space, row);
			if (width > 0) {
				if (index < recipes.size()) {
					EmiFavorite fav = recipes.get(index);
					EmiRecipe recipe = fav.getRecipe();
					if (recipe != null && recipe.getId() != null) {
						long batches = Math.max(1, solvedBatches.getOrDefault(recipe.getId(), Math.max(1, BATCHES.getOrDefault(recipe.getId(), 1L))));
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

	private static Map<Identifier, Long> solvePage(List<EmiFavorite> recipes) {
		Map<Identifier, Long> solved = Maps.newHashMap();
		Map<String, Long> demand = Maps.newHashMap();
		for (EmiFavorite fav : recipes) {
			EmiRecipe recipe = fav.getRecipe();
			if (recipe == null || recipe.getId() == null) {
				continue;
			}
			Identifier id = recipe.getId();
			long manual = Math.max(1, BATCHES.getOrDefault(id, 1L));
			EmiIngredient output = firstOutput(recipe);
			String outputKey = stackKey(output);
			long outputPerBatch = Math.max(1, output.getAmount());
			long requiredOutput = outputKey == null ? 0 : Math.max(0, demand.getOrDefault(outputKey, 0L));
			long neededBatches = requiredOutput <= 0 ? 0 : (requiredOutput + outputPerBatch - 1) / outputPerBatch;
			long batches = Math.max(manual, neededBatches);
			solved.put(id, batches);

			if (outputKey != null) {
				long produced = outputPerBatch * batches;
				long remaining = Math.max(0, requiredOutput - produced);
				if (remaining == 0) {
					demand.remove(outputKey);
				} else {
					demand.put(outputKey, remaining);
				}
			}

			for (EmiIngredient input : recipe.getInputs()) {
				String key = stackKey(input);
				if (key == null) {
					continue;
				}
				long amount = Math.max(1, input.getAmount()) * batches;
				demand.put(key, demand.getOrDefault(key, 0L) + amount);
			}
		}
		return solved;
	}

	private static String stackKey(EmiIngredient ingredient) {
		if (ingredient == null || ingredient.isEmpty() || ingredient.getEmiStacks().isEmpty()) {
			return null;
		}
		ItemStack stack = ingredient.getEmiStacks().get(0).getItemStack();
		if (stack.isEmpty()) {
			return null;
		}
		Identifier id = EmiPort.getItemRegistry().getId(stack.getItem());
		if (id == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(id.toString());
		if (stack.hasNbt() && stack.getNbt() != null) {
			sb.append('|').append(stack.getNbt());
		}
		return sb.toString();
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
			ORDER.remove(id);
			bump();
			EmiPersistentData.save();
		}
		return removed;
	}

	public static boolean reorderRecipe(EmiRecipe dragged, EmiRecipe target, boolean after, int fallbackPage) {
		if (dragged == null || dragged.getId() == null) {
			return false;
		}
		Identifier draggedId = dragged.getId();
		Map<Identifier, EmiFavorite> bookmarks = collectBookmarks();
		if (!bookmarks.containsKey(draggedId)) {
			return false;
		}
		ensureDefaults(bookmarks);

		int page = Math.max(0, fallbackPage);
		Identifier targetId = null;
		if (target != null && target.getId() != null && bookmarks.containsKey(target.getId())) {
			targetId = target.getId();
			page = Math.max(0, PAGES.getOrDefault(targetId, page));
		}
		PAGES.put(draggedId, page);

		List<Identifier> ordered = getOrderedPageIds(page, bookmarks);
		ordered.remove(draggedId);
		int insert = ordered.size();
		if (targetId != null) {
			int targetIndex = ordered.indexOf(targetId);
			if (targetIndex >= 0) {
				insert = targetIndex + (after ? 1 : 0);
			}
		}
		ordered.add(Math.max(0, Math.min(insert, ordered.size())), draggedId);
		for (int i = 0; i < ordered.size(); i++) {
			ORDER.put(ordered.get(i), i);
		}
		bump();
		EmiPersistentData.save();
		return true;
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
			ORDER.put(id, maxOrderForPage(currentPage) + 1);
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

	public static JsonObject saveOrder() {
		pruneInvalidFavorites();
		JsonObject json = new JsonObject();
		for (Map.Entry<Identifier, Integer> entry : ORDER.entrySet()) {
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

	public static void loadOrder(JsonObject json) {
		ORDER.clear();
		Set<Identifier> validIds = Sets.newHashSet();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				validIds.add(fav.getRecipe().getId());
			}
		}
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			Identifier id = Identifier.tryParse(entry.getKey());
			if (id != null && validIds.contains(id) && entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
				ORDER.put(id, Math.max(0, entry.getValue().getAsInt()));
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
		ORDER.keySet().removeIf(id -> !validIds.contains(id));
	}

	private static List<Identifier> getOrderedPageIds(int page, Map<Identifier, EmiFavorite> bookmarks) {
		List<Identifier> ids = Lists.newArrayList();
		for (Identifier id : bookmarks.keySet()) {
			if (Math.max(0, PAGES.getOrDefault(id, 0)) == page) {
				ids.add(id);
			}
		}
		ids.sort(Comparator.comparingInt((Identifier id) -> ORDER.getOrDefault(id, Integer.MAX_VALUE))
				.thenComparing(Identifier::toString));
		return ids;
	}

	private static int maxOrderForPage(int page) {
		int max = -1;
		for (Map.Entry<Identifier, Integer> entry : ORDER.entrySet()) {
			if (Math.max(0, PAGES.getOrDefault(entry.getKey(), 0)) == page) {
				max = Math.max(max, entry.getValue());
			}
		}
		return max;
	}

	private static int rowsPerPage(EmiScreenManager.ScreenSpace space) {
		int rows = 0;
		for (int row = 0; row < space.th; row++) {
			if (widthForRow(space, row) > 0) {
				rows++;
			}
		}
		return rows;
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