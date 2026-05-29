package dev.emi.emi.screen;

import java.util.List;

import com.google.common.collect.Lists;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.config.SidebarSide;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class RecipeTab {
	private static final int RECIPE_PADDING = 10;
	private final List<RecipeDisplay> allDisplays;
	private List<RecipeDisplay> filteredDisplays;
	private final int width;
	private List<List<RecipeDisplay>> pages = Lists.newArrayList();
	public final EmiRecipeCategory category;

	public RecipeTab(EmiRecipeCategory category, List<EmiRecipe> recipes) {
		this.category = category;
		allDisplays = recipes.stream().map(r -> {
			try {
				return new RecipeDisplay(r);
			} catch (Throwable t) {
				return new RecipeDisplay(t);
			}
		}).toList();
		filteredDisplays = allDisplays;
		width = allDisplays.stream().map(RecipeDisplay::getWidth).max(Integer::compareTo).orElse(0);
	}

	public void setFilter(String query) {
		if (query == null || query.isEmpty()) {
			filteredDisplays = allDisplays;
		} else {
			String lowerQuery = query.toLowerCase();
			filteredDisplays = allDisplays.stream().filter(d -> matchesQuery(d, lowerQuery)).toList();
		}
	}

	public boolean hasFilteredResults() {
		return !filteredDisplays.isEmpty();
	}

	private static boolean matchesQuery(RecipeDisplay display, String query) {
		if (display.recipe == null) {
			return false;
		}
		EmiRecipe recipe = display.recipe;
		Identifier id = recipe.getId();
		if (id != null && id.toString().toLowerCase().contains(query)) {
			return true;
		}
		for (EmiStack stack : recipe.getOutputs()) {
			if (stackMatchesQuery(stack, query)) {
				return true;
			}
		}
		for (EmiIngredient ingredient : recipe.getInputs()) {
			for (EmiStack stack : ingredient.getEmiStacks()) {
				if (stackMatchesQuery(stack, query)) {
					return true;
				}
			}
		}
		for (EmiIngredient ingredient : recipe.getCatalysts()) {
			for (EmiStack stack : ingredient.getEmiStacks()) {
				if (stackMatchesQuery(stack, query)) {
					return true;
				}
			}
		}
		return false;
	}

	static boolean ingredientMatchesQuery(EmiIngredient ingredient, String query) {
		for (EmiStack stack : ingredient.getEmiStacks()) {
			if (stackMatchesQuery(stack, query)) {
				return true;
			}
		}
		return false;
	}

	private static boolean stackMatchesQuery(EmiStack stack, String query) {
		Text name = stack.getName();
		if (name != null && name.getString().toLowerCase().contains(query)) {
			return true;
		}
		Identifier id = stack.getId();
		if (id != null && id.toString().toLowerCase().contains(query)) {
			return true;
		}
		return false;
	}

	public List<WidgetGroup> constructWidgets(int page, int x, int y, int backgroundWidth, int backgroundHeight, int recipeTopOffset) {
		List<WidgetGroup> groups = Lists.newArrayList();
		int width = backgroundWidth - 16;
		int height = getVerticalRecipeSpace(backgroundHeight);
		int off = 0;
		for (RecipeDisplay display : pages.get(page)) {
			int wx = x + 8;
			int wy = y + recipeTopOffset + off;
			groups.add(display.getWidgets(wx, wy, width, height));
			off += display.getHeight() + RECIPE_PADDING;
		}
		return groups;
	}

	private int getVerticalRecipeSpace(int backgroundHeight) {
		int height = backgroundHeight - 46;
		if (EmiConfig.workstationLocation == SidebarSide.BOTTOM) {
			if (!EmiApi.getRecipeManager().getWorkstations(category).isEmpty() || RecipeScreen.resolve != null) {
				height -= 23;
			}
		}
		return height;
	}

	public void bakePages(int height) {
		height = getVerticalRecipeSpace(height);
		pages.clear();
		List<RecipeDisplay> current = Lists.newArrayList();
		int h = 0;
		for (RecipeDisplay recipe : filteredDisplays) {
			int rh = recipe.getHeight();
			if (!current.isEmpty() && h + rh > height) {
				pages.add(current);
				current = Lists.newArrayList();
				h = 0;
			}
			h += rh + RECIPE_PADDING;
			current.add(recipe);
		}
		if (!current.isEmpty()) {
			pages.add(current);
		}
	}

	public int getWidth() {
		return width;
	}

	public int getPageCount() {
		return pages.size();
	}

	public List<RecipeDisplay> getPage(int page) {
		if (page >= 0 && page < getPageCount()) {
			return pages.get(page);
		}
		return List.of();
	}
}
