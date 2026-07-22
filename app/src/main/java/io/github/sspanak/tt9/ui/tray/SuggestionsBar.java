package io.github.sspanak.tt9.ui.tray;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.Vibration;
import io.github.sspanak.tt9.ui.main.ResizableMainView;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.TextTools;
import io.github.sspanak.tt9.util.chars.Characters;
import io.github.sspanak.tt9.util.sys.Clipboard;

public class SuggestionsBar {
	public static final String CLIPBOARD_SUGGESTION_SUFFIX = "\u200B...\u200B";
	public static final String SHOW_GROUP_0_SUGGESTION = "(…\u200A)";
	public static final String SHOW_GROUP_1_SUGGESTION = "(…\u200B)";

	// KT9 fork: the punctuation grid is a fixed 7 columns; extra characters wrap to new rows.
	private static final int PUNCTUATION_GRID_COLUMNS = 7;

	private final String SHOW_MORE_SUGGESTION = "(...)";
	private final String STEM_SUFFIX = "… +";
	private final String STEM_VARIATION_PREFIX = "…";
	private final String STEM_PUNCTUATION_VARIATION_PREFIX = "​";
	@NonNull private String stem = "";

	private int defaultBackgroundColor = Color.TRANSPARENT;
	private int backgroundColor;
	private int suggestionSeparatorColor;


	private boolean containsOnlyGuesses = false;
	private int lastScrollIndex = 0;
	private int selectedIndex = 0;
	@Nullable private List<String> suggestions = new ArrayList<>();
	@NonNull private final List<String> visibleSuggestions = new ArrayList<>();

	private final DefaultItemAnimator animator = new DefaultItemAnimator();
	@NonNull private final Runnable onItemClick;
	@NonNull private final Runnable onItemLongClick;
	@Nullable private final RecyclerView mView;
	@NonNull private final ResizableMainView punctuationMainView;
	private boolean isPunctuationGrid = false;
	private final SettingsStore settings;
	private SuggestionsAdapter mSuggestionsAdapter;
	private Vibration vibration;

	private final Handler displayHandler = new Handler(Looper.getMainLooper());


	public SuggestionsBar(@NonNull SettingsStore settings, @NonNull ResizableMainView mainView, @NonNull Runnable onItemClick, @NonNull Runnable onItemLongClick) {
		this.onItemClick = onItemClick;
		this.onItemLongClick = onItemLongClick;
		this.settings = settings;
		this.punctuationMainView = mainView;

		mView = mainView.getView() != null ? mainView.getView().findViewById(R.id.suggestions_bar) : null;
		if (mView != null) {
			Context context = mainView.getView().getContext();

			mView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));

			initDataAdapter(context);
			initSeparator(context);
			addGridDivider();
			configureAnimation();
			setVisible(settings.getShowSuggestions());
			vibration = new Vibration(settings, mView);
		}
	}


	private void configureAnimation() {
		if (mView == null) {
			return;
		}

		animator.setMoveDuration(SettingsStore.SUGGESTIONS_SELECT_ANIMATION_DURATION);
		animator.setChangeDuration(SettingsStore.SUGGESTIONS_TRANSLATE_ANIMATION_DURATION);
		animator.setAddDuration(SettingsStore.SUGGESTIONS_TRANSLATE_ANIMATION_DURATION);
		animator.setRemoveDuration(SettingsStore.SUGGESTIONS_TRANSLATE_ANIMATION_DURATION);
		// KT9 fork: don't animate item CHANGES. Moving the highlight calls notifyItemChanged on the old
		// and new word, and the default cross-fade makes the cells appear to shuffle/resize slightly as
		// you traverse. Disabling change animations makes the highlight move crisply; move/add/remove
		// animations (used for scrolling) are unaffected.
		animator.setSupportsChangeAnimations(false);
	}


	private void initDataAdapter(Context context) {
		if (mView == null) {
			return;
		}

		int suggestionLayout;
		if (settings.isMainLayoutNumpad()) {
			suggestionLayout = R.layout.suggestion_list_numpad;
		} else if (settings.isMainLayoutClassic()) {
			suggestionLayout = R.layout.suggestion_list_classic;
		} else {
			suggestionLayout = R.layout.suggestion_list_small;
		}

		mSuggestionsAdapter = new SuggestionsAdapter(
			context,
			(position) -> handleItemAction(position, false),
			(position) -> handleItemAction(position, true),
			suggestionLayout,
			R.id.suggestion_list_item,
			visibleSuggestions
		);

		mView.setAdapter(mSuggestionsAdapter);
		mView.setHasFixedSize(true); // Optimizes performance

		setColorScheme();
	}


	// KT9 fork: draw grid lines around every cell while the punctuation grid is showing (a cell
	// border on each item). Inert for the normal single-row suggestions (not a GridLayoutManager).
	private void addGridDivider() {
		if (mView == null) {
			return;
		}

		final android.graphics.Paint paint = new android.graphics.Paint();
		paint.setStyle(android.graphics.Paint.Style.STROKE);
		paint.setColor(suggestionSeparatorColor);
		paint.setStrokeWidth(Math.max(1f, mView.getResources().getDisplayMetrics().density));

		mView.addItemDecoration(new RecyclerView.ItemDecoration() {
			@Override
			public void onDrawOver(@NonNull android.graphics.Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
				RecyclerView.LayoutManager lm = parent.getLayoutManager();
				float w = parent.getWidth();
				float h = parent.getHeight();

				if (lm instanceof GridLayoutManager) {
					// Punctuation grid: a vertical line per column boundary (full height), plus a
					// horizontal line at the top and bottom of every visible row so the lines scroll
					// correctly with the rows.
					int cols = ((GridLayoutManager) lm).getSpanCount();
					for (int col = 1; col < cols; col++) {
						float x = w * col / cols;
						c.drawLine(x, 0, x, h, paint);
					}
					for (int i = 0; i < parent.getChildCount(); i++) {
						View child = parent.getChildAt(i);
						c.drawLine(0, child.getTop(), w, child.getTop(), paint);
						c.drawLine(0, child.getBottom(), w, child.getBottom(), paint);
					}
				} else {
					// Normal single row: close every cell — a line on the right of each item (incl. the
					// last, which closes its cell) plus a line on the left of the first item.
					for (int i = 0; i < parent.getChildCount(); i++) {
						View child = parent.getChildAt(i);
						c.drawLine(child.getRight(), child.getTop(), child.getRight(), child.getBottom(), paint);
						if (i == 0) {
							c.drawLine(child.getLeft(), child.getTop(), child.getLeft(), child.getBottom(), paint);
						}
					}
				}
			}
		});
	}


	private void initSeparator(Context context) {
		if (mView == null) {
			return;
		}

		// KT9 fork: the androidx DividerItemDecoration draws doubled/misplaced lines in a grid, so all
		// separators (both the normal single row and the punctuation grid) are drawn by addGridDivider().
		suggestionSeparatorColor = settings.getSuggestionSeparatorColor();
	}


	public void setVisible(boolean yes) {
		if (mView != null) {
			mView.setVisibility(yes ? View.VISIBLE : View.INVISIBLE);
		}
	}


	public boolean isEmpty() {
		return visibleSuggestions.isEmpty();
	}


	public boolean containsOnlyGuesses() {
		return containsOnlyGuesses && suggestions != null && !suggestions.isEmpty();
	}


	public boolean containsStem() {
		return !stem.isEmpty();
	}


	public int getCurrentIndex() {
		return selectedIndex;
	}


	@NonNull
	public String get(int id) {
		String suggestion = getRaw(id);

		// clipboard abbreviated suggestion
		if (suggestion.endsWith(CLIPBOARD_SUGGESTION_SUFFIX) && suggestions != null) {
			suggestion = Clipboard.get(suggestions.size() - id - 1);
		}

		// show more...
		if (suggestion.equals(SHOW_MORE_SUGGESTION) || suggestion.equalsIgnoreCase(SHOW_GROUP_1_SUGGESTION) || suggestion.equalsIgnoreCase(SHOW_GROUP_0_SUGGESTION)) {
			return Characters.PLACEHOLDER;
		}

		// single char
		if (suggestion.equals(Characters.NEW_LINE)) return "\n";
		if (suggestion.equals(Characters.TAB)) return "\t";

		suggestion = suggestion.replace(Characters.ZWNJ_GRAPHIC, Characters.ZWNJ);
		suggestion = suggestion.replace(Characters.ZWJ_GRAPHIC, Characters.ZWJ);
		if (suggestion.length() == 1) return suggestion;


		// combined with "... +"
		int endIndex = suggestion.indexOf(STEM_SUFFIX);
		endIndex = endIndex == -1 ? suggestion.length() : endIndex;

		// "..." prefix
		int startIndex = 0;
		String[] prefixes = {STEM_VARIATION_PREFIX, STEM_PUNCTUATION_VARIATION_PREFIX, Characters.COMBINING_BASE};
		for (String prefix : prefixes) {
			int prefixIndex = suggestion.indexOf(prefix) + 1;
			if (prefixIndex < endIndex) { // do not match the prefix chars when they are part of STEM_SUFFIX
				startIndex = Math.max(startIndex, prefixIndex);
			}
    }

		if (startIndex == 0 && endIndex == suggestion.length()) {
			return suggestion;
		}

		return stem + suggestion.substring(startIndex, endIndex);
	}


	@NonNull
	public String getRaw(int id) {
		final int index = containsStem() ? id - 1 : id;
		if (index < 0 || suggestions == null || index >= suggestions.size()) {
			return "";
		}

		return suggestions.get(index);
	}


	public void setRTL(boolean yes) {
		if (mView != null) {
			mView.setLayoutDirection(yes ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
		}
	}


	public void addMany(@NonNull List<String> extra, boolean append) {
		if (extra.isEmpty()) {
			return;
		}

		if (suggestions == null || suggestions.isEmpty()) {
			setMany(extra, 0, false);
			containsOnlyGuesses = true;
			return;
		} else {
			containsOnlyGuesses = false;
		}

		final List<String> firstList = append ? suggestions : extra;
		final List<String> secondList = append ? extra : suggestions;
		final ArrayList<String> combined = new ArrayList<>(extra.size() + suggestions.size());

		combined.addAll(firstList);

		for (String s : secondList) {
			if (!firstList.contains(s)) {
				combined.add(s);
			}
		}

		setMany(combined, 0, false);
	}


	public void setMany(@Nullable List<String> newSuggestions, int initialSel, boolean containsGenerated) {
		if ((suggestions == null || suggestions.isEmpty()) && (newSuggestions == null || newSuggestions.isEmpty())) {
			return;
		}

		containsOnlyGuesses = false;
		suggestions = newSuggestions;
		selectedIndex = newSuggestions == null || newSuggestions.isEmpty() ? 0 : Math.max(initialSel, 0);

		visibleSuggestions.clear();
		setStem(newSuggestions, containsGenerated);

		boolean onlySpecialChars = newSuggestions != null && !newSuggestions.isEmpty() && !(new Text(newSuggestions.get(0)).isAlphabetic());
		addManyVisible(newSuggestions, mView == null || onlySpecialChars ? Integer.MAX_VALUE : SettingsStore.SUGGESTIONS_MAX);

		// KT9 fork: only the key-1/"*" punctuation set becomes the 2-row grid. It is the only panel that
		// starts with the return key ("\n"), so other special-char panels (e.g. the 0/space key) stay a
		// normal single row.
		boolean isPunctuationSet = newSuggestions != null && !newSuggestions.isEmpty() && "\n".equals(newSuggestions.get(0));
		updatePunctuationGrid(isPunctuationSet);

		// KT9 fork: single-character suggestions get fixed uniform cells so nothing shifts or scrunches
		// while navigating; words keep variable-width cells. This also covers the single-character
		// punctuation panels (e.g. the "@ _ . ! ? ..." set email fields show) so they stay still like
		// the letter cells. The only single-character panel excluded is the 2-row punctuation grid,
		// which manages its own layout.
		mSuggestionsAdapter.setLetterMode(!isPunctuationSet && allSingleCharacters(visibleSuggestions), letterCellWidth());

		selectedIndex = Math.max(Math.min(selectedIndex, visibleSuggestions.size() - 1), 0);

		render();
	}


	private static boolean allSingleCharacters(@NonNull List<String> items) {
		if (items.isEmpty()) {
			return false;
		}
		for (String s : items) {
			if (s == null || s.length() != 1) {
				return false;
			}
		}
		return true;
	}


	private int letterCellWidth() {
		return mView == null ? 0 : Math.round(40 * mView.getResources().getDisplayMetrics().density);
	}


	/**
	 * KT9 fork: switch the suggestion list between a single horizontal row (normal) and a 2-row grid
	 * (punctuation). When it toggles, poke the main view so the keyboard grows/shrinks to fit the
	 * extra row (MainLayoutTray.getStatusBarHeight is punctuation-aware).
	 */
	private void updatePunctuationGrid(boolean useGrid) {
		if (mView == null || useGrid == isPunctuationGrid) {
			return;
		}
		isPunctuationGrid = useGrid;
		mSuggestionsAdapter.setGridMode(useGrid, computeGridHeight() / 2);

		android.view.ViewGroup.LayoutParams lp = mView.getLayoutParams();
		if (useGrid) {
			// Fixed 7-column, row-major fill: extra characters flow into new rows below (not extra
			// columns), so the grid grows downward and scrolls, with 2 rows visible at a time.
			mView.setLayoutManager(new GridLayoutManager(mView.getContext(), PUNCTUATION_GRID_COLUMNS, RecyclerView.VERTICAL, false));
			// Explicit height so exactly two rows are given room; the rest scroll into view.
			lp.height = computeGridHeight();
			// Yellow scrollbar on the right (matches the highlight color) while the grid is showing.
			mView.setVerticalScrollBarEnabled(true);
			mView.setScrollbarFadingEnabled(false);
		} else {
			mView.setLayoutManager(new LinearLayoutManager(mView.getContext(), RecyclerView.HORIZONTAL, false));
			lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
			mView.setVerticalScrollBarEnabled(false);
		}
		mView.setLayoutParams(lp);

		// Poke the main view so the keyboard grows/shrinks to fit one vs two rows. Deferred so it
		// runs after the current layout pass.
		displayHandler.post(punctuationMainView::render);
	}


	// KT9 fork: the 2-row punctuation grid height, computed from the text size (same formula as
	// MainLayoutTray.getStatusBarHeight) so the RecyclerView and the keyboard height always match.
	private int computeGridHeight() {
		float textSize = mView.getResources().getDimension(R.dimen.status_bar_text_size);
		float padding = Math.max(textSize * 0.45f, 1f);
		int oneRow = Math.round((padding + textSize) * settings.getSuggestionFontScale());
		return Math.round(oneRow * SettingsStore.PUNCTUATION_GRID_ROWS_FACTOR);
	}


	private void setStem(List<String> newSuggestions, boolean containsGenerated) {
		if (newSuggestions == null || newSuggestions.size() < 2) {
			stem = "";
			return;
		}

		stem = containsGenerated && newSuggestions.get(0).length() > 1 ? newSuggestions.get(0).substring(0, newSuggestions.get(0).length() - 1) : "";

		// Do not modify single letter + punctuation, such as "j'" or "l'". They look better as they are.
		stem = (stem.length() == 1 && newSuggestions.get(0).length() == 2 && !Character.isAlphabetic(newSuggestions.get(0).charAt(1))) ? "" : stem;

		// If no other suggestion contains the stem, it makes no sense to separate them and display:
		// "STEM" + "one-suggestion". It is only useful when there are multiple suggestions with the
		// same stem.
		boolean onlyOneContainsStem = true;
		for (int i = 1; i < newSuggestions.size(); i++) {
			if (newSuggestions.get(i).contains(stem)) {
				onlyOneContainsStem = false;
				break;
			}
		}
		stem = onlyOneContainsStem ? "" : stem;

		if (!stem.isEmpty() && !newSuggestions.contains(stem)) {
			visibleSuggestions.add(stem + STEM_SUFFIX);
			selectedIndex++;
		}
	}


	public void setTextCase(@NonNull Language language, int textCase) {
		if (suggestions == null || suggestions.isEmpty()) {
			return;
		}

		final ArrayList<String> copy = new ArrayList<>(suggestions);
		copy.replaceAll(text -> new Text(language, text).toTextCase(textCase));
		final boolean onlyGuesses = containsOnlyGuesses();
		setMany(copy, selectedIndex, onlyGuesses);
		containsOnlyGuesses = onlyGuesses;
	}


	/**
	 * Adds suggestions to the list displayed on the screen. By default, they should be limited
	 * for performance reasons, hence the "limit" parameter. When they are too many, the SHOW_MORE_SUGGESTION,
	 * will be displayed at the end.
	 */
	private void addManyVisible(List<String> newSuggestions, int limit) {
		if (newSuggestions == null) {
			return;
		}

		for (int i = 0, end = Math.min(limit, newSuggestions.size()); i < end; i++) {
			addVisible(newSuggestions.get(i));
		}

		if (newSuggestions.size() > limit) {
			visibleSuggestions.add(SHOW_MORE_SUGGESTION);
		}
	}


	private void addVisible(@NonNull String suggestion) {
		// shorten the stem variations
		if (!stem.isEmpty() && suggestion.length() == stem.length() + 1 && suggestion.toLowerCase().startsWith(stem.toLowerCase())) {
			String trimmedSuggestion = suggestion.substring(stem.length());
			char firstChar = trimmedSuggestion.charAt(0);

			String prefix = Character.isAlphabetic(firstChar) && !Characters.isCombiningPunctuation(firstChar) ? STEM_VARIATION_PREFIX : STEM_PUNCTUATION_VARIATION_PREFIX;
			prefix = Characters.isFathatan(firstChar) ? " " : prefix; // Fix incorrect display of Fathatan without a base character. It is a combining character, but since it is a letter, we must include a base character not to break it, with a "..." prefix
			visibleSuggestions.add(prefix + formatUnreadableSuggestion(trimmedSuggestion));
			return;
		}

		visibleSuggestions.add(formatUnreadableSuggestion(suggestion));
	}


	private void render() {
		if (mView == null) {
			return;
		}

		final boolean isVisible = mView.getVisibility() == View.VISIBLE;

		if (isVisible) {
			setBackground(false);
			mSuggestionsAdapter.setTextSize(settings.getSuggestionFontScale());

			boolean smooth = settings.getSuggestionSmoothScroll() && visibleSuggestions.size() <= SettingsStore.SUGGESTIONS_MAX + 1;
			mView.setItemAnimator(smooth ? animator : null);
		}

		mSuggestionsAdapter.resetItems(selectedIndex);
		// KT9 fork: never scroll the punctuation grid (all characters are visible; scrolling shifts them).
		if (isVisible && selectedIndex > 0 && !isPunctuationGrid) {
			mView.scrollToPosition(selectedIndex);
		}
	}


	/**
	 * If addManyVisible() constrained the visible suggestions, the end of the list will contain
	 * the SHOW_MORE_SUGGESTION. This method will remove the SHOW_MORE_SUGGESTION, prepare
	 * all hidden suggestions for displaying, and will scroll correctly to the new visible suggestion.
	 * After that, you must call render(), to visualize the changes.
	 */
	private boolean appendHiddenSuggestionsIfNeeded(boolean scrollBack) {
		if (mView == null || selectedIndex < 0 || selectedIndex >= visibleSuggestions.size() || !visibleSuggestions.get(selectedIndex).equals(SHOW_MORE_SUGGESTION)) {
			return false;
		}

		visibleSuggestions.clear();
		addManyVisible(suggestions, Integer.MAX_VALUE);
		selectedIndex = scrollBack || selectedIndex >= visibleSuggestions.size() ? visibleSuggestions.size() - 1 : selectedIndex;
		selectedIndex = Math.max(selectedIndex, 0);

		return true;
	}


	private String formatUnreadableSuggestion(String suggestion) {
		if (TextTools.isCombining(suggestion)) {
			return Characters.COMBINING_BASE + suggestion;
		}

		return switch (suggestion) {
			case "\n" -> Characters.NEW_LINE;
			case "\t" -> Characters.TAB;
			case Characters.ZWJ -> Characters.ZWJ_GRAPHIC;
			case Characters.ZWNJ -> Characters.ZWNJ_GRAPHIC;
			default -> suggestion;
		};
	}


	public void scrollToSuggestion(int increment) {
		if (visibleSuggestions.size() <= 1) {
			return;
		}

		calculateScrollIndex(increment);
		if (appendHiddenSuggestionsIfNeeded(increment < 0)) {
			render();
		}
		scrollToSelected();
	}


	private void calculateScrollIndex(int increment) {
		if (visibleSuggestions.isEmpty()) {
			selectedIndex = 0;
			return;
		}

		final int size = visibleSuggestions.size();

		// KT9 fork: punctuation grid navigation.
		if (isPunctuationGrid) {
			final int cols = PUNCTUATION_GRID_COLUMNS;
			if (Math.abs(increment) == cols) {
				// Vertical move (d-pad up/down): keep the SAME column and wrap top<->bottom. The last row
				// may be partial, so when the target cell in this column does not exist, jump to the
				// nearest existing cell in the same column (top for down, previous row for up).
				final int rows = (size + cols - 1) / cols;
				int col = selectedIndex % cols;
				int row = selectedIndex / cols;
				if (increment > 0) {
					row = (row + 1) % rows;
					if (row * cols + col >= size) {
						row = 0; // wrapped onto the partial last row's missing cell -> top of the column
					}
				} else {
					row = (row - 1 + rows) % rows;
					if (row * cols + col >= size) {
						row = (row - 1 + rows) % rows; // skip the partial last row's missing cell
					}
				}
				selectedIndex = row * cols + col;
			} else {
				// Horizontal move (d-pad left/right): step through reading order, wrapping at the ends.
				selectedIndex = ((selectedIndex + increment) % size + size) % size;
			}
			return;
		}

		selectedIndex = selectedIndex + increment;
		if (selectedIndex == size) {
			selectedIndex = containsStem() ? 1 : 0;
		} else if (selectedIndex < 0) {
			selectedIndex = size - 1;
		} else if (selectedIndex == 0 && containsStem()) {
			selectedIndex = size - 1;
		}
	}


	private void scrollToSelected() {
		if (mView == null) {
			return;
		}

		mSuggestionsAdapter.setSelection(selectedIndex);

		if (settings.getSuggestionScrollingDelay() > 0) {
			displayHandler.removeCallbacksAndMessages(null);
			displayHandler.postDelayed(this::renderScroll, settings.getSuggestionScrollingDelay());
		} else {
			renderScroll();
		}
	}


	/**
	 * Tells the adapter to scroll. Always call scrollToSelected() first,
	 * to set the selected index in the adapter.
	 */
	private void renderScroll() {
		if (mView == null || mView.getVisibility() != View.VISIBLE) {
			return;
		}

		// KT9 fork: punctuation grid — snap-scroll by whole rows. Keep the currently visible rows fixed
		// while the highlight moves among them, and only when the selected row falls outside the viewport
		// do we scroll, snapping a whole row to the top. This avoids the per-move nudge/jitter and never
		// leaves a row half-shown (which used to make the highlight look deselected).
		if (isPunctuationGrid && mView.getLayoutManager() instanceof GridLayoutManager) {
			GridLayoutManager glm = (GridLayoutManager) mView.getLayoutManager();
			int cols = glm.getSpanCount();
			int rowHeight = Math.max(1, computeGridHeight() / 2);
			int viewH = mView.getHeight() > 0 ? mView.getHeight() : computeGridHeight();
			int visibleRows = Math.max(1, Math.round((float) viewH / rowHeight));
			int selRow = selectedIndex / cols;
			int firstPos = glm.findFirstVisibleItemPosition();
			int topRow = firstPos < 0 ? 0 : firstPos / cols;
			int newTop = topRow;
			if (selRow < topRow) {
				newTop = selRow;
			} else if (selRow > topRow + visibleRows - 1) {
				newTop = selRow - (visibleRows - 1);
			}
			if (newTop != topRow) {
				glm.scrollToPositionWithOffset(newTop * cols, 0);
			}
			lastScrollIndex = selectedIndex;
			return;
		}

		// KT9 fork: only scroll when the selected item is off-screen. Navigating among already-visible
		// items must not scroll — otherwise the row shifts and the cells appear to change.
		if (mView.getLayoutManager() instanceof LinearLayoutManager) {
			LinearLayoutManager llm = (LinearLayoutManager) mView.getLayoutManager();
			int first = llm.findFirstCompletelyVisibleItemPosition();
			int last = llm.findLastCompletelyVisibleItemPosition();
			if (first >= 0 && selectedIndex >= first && selectedIndex <= last) {
				lastScrollIndex = selectedIndex;
				return;
			}
		}

		boolean smooth = settings.getSuggestionSmoothScroll() && Math.abs(selectedIndex - lastScrollIndex) < SettingsStore.SUGGESTIONS_MAX;
		mView.setItemAnimator(smooth ? animator : null);

		// KT9 fork: soften the scroll. Instead of snapping the selected word to the left edge (a big
		// shift of the whole row), scroll the minimum needed to bring it just into view at the nearest
		// edge - so the row nudges by roughly one word instead of leaping.
		final int scrollTarget = containsStem() && selectedIndex == 1 ? 0 : selectedIndex;
		RecyclerView.LayoutManager lm = mView.getLayoutManager();
		if (lm instanceof LinearLayoutManager) {
			LinearSmoothScroller scroller = new LinearSmoothScroller(mView.getContext()) {
				@Override protected int getHorizontalSnapPreference() { return SNAP_TO_ANY; }
			};
			scroller.setTargetPosition(scrollTarget);
			lm.startSmoothScroll(scroller);
		} else {
			mView.scrollToPosition(scrollTarget);
		}
		lastScrollIndex = selectedIndex;
	}


	/**
	 * setColorScheme
	 * Changes the suggestion colors according to the current color scheme.
	 */
	public void setColorScheme() {
		if (mView == null) {
			return;
		}

		defaultBackgroundColor = settings.getKeyboardBackground();
		mSuggestionsAdapter.setColorDefault(settings.getKeyboardTextColor());
		mSuggestionsAdapter.setColorHighlight(settings.getSuggestionSelectedColor());
		mSuggestionsAdapter.setBackgroundHighlight(settings.getSuggestionSelectedBackground());
		suggestionSeparatorColor = settings.getSuggestionSeparatorColor();
		initSeparator(mView.getContext());

		// KT9 fork: the punctuation-grid scrollbar thumb, tinted to the highlight yellow.
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			float density = mView.getResources().getDisplayMetrics().density;
			android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
			thumb.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			thumb.setColor(settings.getSuggestionSelectedBackground());
			thumb.setCornerRadius(2f * density);
			thumb.setSize(Math.round(3f * density), Math.round(3f * density));
			mView.setVerticalScrollbarThumbDrawable(thumb);
			mView.setScrollBarSize(Math.round(4f * density));
		}

		setBackground(true);
	}


	/**
	 * setBackground
	 * Makes the background transparent, when there are no suggestions and theme-colored,
	 * when there are suggestions.
	 */
	private void setBackground(boolean force) {
		if (mView == null) {
			return;
		}

		boolean mustChange = (
			(backgroundColor == Color.TRANSPARENT && !visibleSuggestions.isEmpty()) ||
			(backgroundColor == defaultBackgroundColor && visibleSuggestions.isEmpty())
		);

		if (force || mustChange) {
			backgroundColor = visibleSuggestions.isEmpty() ? Color.TRANSPARENT : defaultBackgroundColor;
			mView.setBackgroundColor(backgroundColor);
		}
	}

	/**
	 * handleItemClick
	 * Passes through suggestion selected using the touchscreen.
	 */
	private void handleItemAction(int position, boolean isLongClick) {
		if (containsStem() && position == 0) {
			return;
		}

		vibration.vibrate();
		selectedIndex = position;
		if (appendHiddenSuggestionsIfNeeded(false)) {
			render();
		} else if (isLongClick) {
			onItemLongClick.run();
		} else {
			onItemClick.run();
		}
	}
}
