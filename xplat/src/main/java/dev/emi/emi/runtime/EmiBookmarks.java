package dev.emi.emi.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.Identifier;

public class EmiBookmarks {
	private static final List<EmiIngredient> FALLBACK = List.of();
	private static final List<List<BookmarkEntry>> BOOKMARK_PAGES = Lists.newArrayList();
	private static final Map<String, List<EmiIngredient>> LAYOUT_CACHE = Maps.newHashMap();
	private static int currentPage = 0;
	private static int version = 0;

	static {
		ensureTrailingEmptyPage();
	}

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
		Map<Identifier, EmiFavorite> bookmarks = collectBookmarks();
		normalizePages(bookmarks);
		List<EmiIngredient> list = Lists.newArrayList();
		if (bookmarks.isEmpty()) {
			return list;
		}

		int pageCapacity = rowsPerPage(space);
		int totalPages = BOOKMARK_PAGES.size();
		int page = 0;
		while (page < totalPages) {
			List<BookmarkEntry> entries = page < BOOKMARK_PAGES.size() ? BOOKMARK_PAGES.get(page) : List.of();
			List<Identifier> ordered = Lists.newArrayList();
			for (BookmarkEntry entry : entries) {
				if (entry != null && entry.recipeId != null && bookmarks.containsKey(entry.recipeId)) {
					ordered.add(entry.recipeId);
				}
			}
			int consumed = Math.min(pageCapacity, ordered.size());
			List<Identifier> visibleIds = ordered.subList(0, consumed);
			List<EmiFavorite> visible = Lists.newArrayList();
			Map<Identifier, Long> manualCounts = Maps.newHashMap();
			for (Identifier id : visibleIds) {
				EmiFavorite fav = bookmarks.get(id);
				if (fav != null) {
					visible.add(fav);
					manualCounts.put(id, Math.max(1, getManualCount(id)));
				}
			}
			Map<Identifier, Long> solvedBatches = solvePage(visible, manualCounts);
			appendPage(space, list, visible, solvedBatches);

			if (consumed < ordered.size()) {
				int nextPage = page + 1;
				ensurePageIndex(nextPage);
				List<BookmarkEntry> next = BOOKMARK_PAGES.get(nextPage);
				for (int i = consumed; i < ordered.size(); i++) {
					Identifier id = ordered.get(i);
					next.add(new BookmarkEntry(id, getManualCount(id)));
					removeEntry(id);
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
						long batches = Math.max(1, solvedBatches.getOrDefault(recipe.getId(), getManualCount(recipe.getId())));
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

	private static Map<Identifier, Long> solvePage(List<EmiFavorite> recipes, Map<Identifier, Long> manualCounts) {
		Map<Identifier, Long> solved = Maps.newHashMap();
		Map<String, Long> demand = Maps.newHashMap();
		for (EmiFavorite fav : recipes) {
			EmiRecipe recipe = fav.getRecipe();
			if (recipe == null || recipe.getId() == null) {
				continue;
			}
			Identifier id = recipe.getId();
			long manual = Math.max(1, manualCounts.getOrDefault(id, 1L));
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
		return adjustBatch(recipe, delta, largeStep, null);
	}

	public static boolean adjustBatch(EmiRecipe recipe, int delta, boolean largeStep, Long displayedBatches) {
		if (recipe == null || recipe.getId() == null || delta == 0) {
			return false;
		}
		long step = largeStep ? defaultStepFor(recipe) : 1;
		long current = Math.max(1, getManualCount(recipe.getId()));
		if (displayedBatches != null) {
			current = Math.max(current, Math.max(1, displayedBatches.longValue()));
		}
		long next = current + step * delta;
		next = Math.max(1, Math.min(Integer.MAX_VALUE, next));
		if (next == current) {
			return false;
		}
		setManualCount(recipe.getId(), next);
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
			removeEntry(id);
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
		normalizePages(bookmarks);

		EntryRef draggedRef = findEntryRef(draggedId);
		if (draggedRef == null) {
			return false;
		}
		BookmarkEntry draggedEntry = draggedRef.entry;
		BOOKMARK_PAGES.get(draggedRef.page).remove(draggedRef.index);

		int page = Math.max(0, fallbackPage);
		EntryRef targetRef = null;
		if (target != null && target.getId() != null) {
			targetRef = findEntryRef(target.getId());
		}
		if (targetRef != null) {
			page = targetRef.page;
		}
		ensurePageIndex(page);
		List<BookmarkEntry> pageEntries = BOOKMARK_PAGES.get(page);
		int insert = pageEntries.size();
		if (targetRef != null && targetRef.page == page) {
			insert = targetRef.index + (after ? 1 : 0);
		}
		insert = Math.max(0, Math.min(insert, pageEntries.size()));
		pageEntries.add(insert, draggedEntry);

		ensureTrailingEmptyPage();
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
		if (findEntryRef(id) != null) {
			// Favorite save can normalize pages before this hook runs; ensure cache updates immediately.
			bump();
			return;
		}
		ensurePageIndex(currentPage);
		BOOKMARK_PAGES.get(currentPage).add(new BookmarkEntry(id, 1));
		ensureTrailingEmptyPage();
		bump();
		EmiPersistentData.save();
	}

	public static JsonArray savePagesData() {
		pruneInvalidFavorites();
		JsonArray pages = new JsonArray();
		for (List<BookmarkEntry> page : BOOKMARK_PAGES) {
			JsonArray arr = new JsonArray();
			for (BookmarkEntry entry : page) {
				if (entry == null || entry.recipeId == null) {
					continue;
				}
				JsonObject obj = new JsonObject();
				obj.addProperty("recipe", entry.recipeId.toString());
				obj.addProperty("count", Math.max(1, entry.count));
				arr.add(obj);
			}
			pages.add(arr);
		}
		return pages;
	}

	public static void loadPagesData(JsonArray pages) {
		BOOKMARK_PAGES.clear();
		Set<Identifier> validIds = Sets.newHashSet();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				validIds.add(fav.getRecipe().getId());
			}
		}
		for (JsonElement pageEl : pages) {
			if (!pageEl.isJsonArray()) {
				continue;
			}
			JsonArray arr = pageEl.getAsJsonArray();
			List<BookmarkEntry> page = Lists.newArrayList();
			for (JsonElement entryEl : arr) {
				if (!entryEl.isJsonObject()) {
					continue;
				}
				JsonObject obj = entryEl.getAsJsonObject();
				if (!JsonHelper.hasString(obj, "recipe")) {
					continue;
				}
				Identifier id = Identifier.tryParse(JsonHelper.getString(obj, "recipe"));
				if (id == null || !validIds.contains(id)) {
					continue;
				}
				long count = 1;
				if (JsonHelper.hasNumber(obj, "count")) {
					count = Math.max(1, JsonHelper.getInt(obj, "count"));
				}
				if (containsEntry(page, id)) {
					continue;
				}
				page.add(new BookmarkEntry(id, count));
			}
			BOOKMARK_PAGES.add(page);
		}
		normalizePages(collectBookmarks());
		bump();
	}

	private static void pruneInvalidFavorites() {
		Set<Identifier> validIds = Sets.newHashSet();
		for (EmiFavorite fav : EmiFavorites.favorites) {
			if (fav.getRecipe() != null && fav.getRecipe().getId() != null) {
				validIds.add(fav.getRecipe().getId());
			}
		}
		for (List<BookmarkEntry> page : BOOKMARK_PAGES) {
			page.removeIf(entry -> entry == null || entry.recipeId == null || !validIds.contains(entry.recipeId));
		}
		normalizePages(collectBookmarks());
	}

	private static void normalizePages(Map<Identifier, EmiFavorite> bookmarks) {
		Set<Identifier> seen = Sets.newHashSet();
		for (List<BookmarkEntry> page : BOOKMARK_PAGES) {
			page.removeIf(entry -> entry == null || entry.recipeId == null || !bookmarks.containsKey(entry.recipeId)
					|| !seen.add(entry.recipeId));
		}
		ensurePageIndex(currentPage);
		List<BookmarkEntry> current = BOOKMARK_PAGES.get(currentPage);
		for (Identifier id : bookmarks.keySet()) {
			if (!seen.contains(id)) {
				current.add(new BookmarkEntry(id, 1));
			}
		}
		ensureTrailingEmptyPage();
		if (currentPage >= BOOKMARK_PAGES.size()) {
			currentPage = Math.max(0, BOOKMARK_PAGES.size() - 1);
		}
	}

	private static void ensurePageIndex(int page) {
		while (BOOKMARK_PAGES.size() <= page) {
			BOOKMARK_PAGES.add(Lists.newArrayList());
		}
	}

	private static void ensureTrailingEmptyPage() {
		if (BOOKMARK_PAGES.isEmpty()) {
			BOOKMARK_PAGES.add(Lists.newArrayList());
			return;
		}
		int i = BOOKMARK_PAGES.size() - 1;
		while (i > 0 && BOOKMARK_PAGES.get(i).isEmpty() && BOOKMARK_PAGES.get(i - 1).isEmpty()) {
			BOOKMARK_PAGES.remove(i--);
		}
		if (!BOOKMARK_PAGES.get(BOOKMARK_PAGES.size() - 1).isEmpty()) {
			BOOKMARK_PAGES.add(Lists.newArrayList());
		}
	}

	private static EntryRef findEntryRef(Identifier id) {
		for (int p = 0; p < BOOKMARK_PAGES.size(); p++) {
			List<BookmarkEntry> page = BOOKMARK_PAGES.get(p);
			for (int i = 0; i < page.size(); i++) {
				BookmarkEntry entry = page.get(i);
				if (entry != null && id.equals(entry.recipeId)) {
					return new EntryRef(p, i, entry);
				}
			}
		}
		return null;
	}

	private static long getManualCount(Identifier id) {
		EntryRef ref = findEntryRef(id);
		if (ref != null) {
			return Math.max(1, ref.entry.count);
		}
		return 1;
	}

	private static void setManualCount(Identifier id, long count) {
		EntryRef ref = findEntryRef(id);
		if (ref != null) {
			ref.entry.count = Math.max(1, count);
		}
	}

	private static void removeEntry(Identifier id) {
		for (List<BookmarkEntry> page : BOOKMARK_PAGES) {
			page.removeIf(entry -> entry != null && id.equals(entry.recipeId));
		}
		ensureTrailingEmptyPage();
	}

	private static boolean containsEntry(List<BookmarkEntry> page, Identifier id) {
		for (BookmarkEntry entry : page) {
			if (entry != null && id.equals(entry.recipeId)) {
				return true;
			}
		}
		return false;
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

	public static long getDisplayedBatch(EmiRecipe recipe, EmiScreenManager.ScreenSpace space) {
		if (recipe == null || recipe.getId() == null || space == null) {
			return 1;
		}
		Identifier id = recipe.getId();
		Map<Identifier, EmiFavorite> bookmarks = collectBookmarks();
		if (!bookmarks.containsKey(id)) {
			return 1;
		}
		normalizePages(bookmarks);

		EntryRef ref = findEntryRef(id);
		if (ref == null) {
			return Math.max(1, getManualCount(id));
		}

		List<BookmarkEntry> entries = BOOKMARK_PAGES.get(ref.page);
		List<Identifier> ordered = Lists.newArrayList();
		for (BookmarkEntry entry : entries) {
			if (entry != null && entry.recipeId != null && bookmarks.containsKey(entry.recipeId)) {
				ordered.add(entry.recipeId);
			}
		}

		int consumed = Math.min(rowsPerPage(space), ordered.size());
		if (ref.index >= consumed) {
			return Math.max(1, getManualCount(id));
		}

		List<EmiFavorite> visible = Lists.newArrayList();
		Map<Identifier, Long> manualCounts = Maps.newHashMap();
		for (int i = 0; i < consumed; i++) {
			Identifier visibleId = ordered.get(i);
			EmiFavorite fav = bookmarks.get(visibleId);
			if (fav != null) {
				visible.add(fav);
				manualCounts.put(visibleId, Math.max(1, getManualCount(visibleId)));
			}
		}

		Map<Identifier, Long> solvedBatches = solvePage(visible, manualCounts);
		return Math.max(1, solvedBatches.getOrDefault(id, getManualCount(id)));
	}

	private static class BookmarkEntry {
		private final Identifier recipeId;
		private long count;

		private BookmarkEntry(Identifier recipeId, long count) {
			this.recipeId = recipeId;
			this.count = Math.max(1, count);
		}
	}

	private static class EntryRef {
		private final int page;
		private final int index;
		private final BookmarkEntry entry;

		private EntryRef(int page, int index, BookmarkEntry entry) {
			this.page = page;
			this.index = index;
			this.entry = entry;
		}
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