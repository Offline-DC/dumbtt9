package io.github.sspanak.tt9.ui.tray;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.function.Consumer;

public class SuggestionsAdapter extends RecyclerView.Adapter<SuggestionsAdapter.ViewHolder> {
	private final Consumer<Integer> onItemClick;
	private final Consumer<Integer> onItemLongClick;
	private final int layout;
	private final int textViewResourceId;
	private final LayoutInflater mInflater;
	private final List<String> mSuggestions;
	private float textSize;

	private int colorDefault;
	private int colorHighlight;
	private int backgroundHighlight;
	private int selectedIndex = 0;
	private boolean gridMode = false; // KT9 fork: punctuation grid vs. normal single row
	private int gridRowHeight = 0;    // KT9 fork: exact height of one grid row (half the grid)
	private int rowPaddingH = -1;     // KT9 fork: the style's horizontal padding, used for word row
	private boolean letterMode = false; // KT9 fork: single-letter suggestions -> fixed uniform cells
	private int letterCellWidth = 0;    // KT9 fork: fixed width of a letter cell


	// KT9 fork: in the punctuation grid, each item fills its cell (width + exact row height) so the
	// glyph centers; in the normal single row, items stay compact (wrap their content).
	public void setGridMode(boolean yes, int rowHeight) {
		gridMode = yes;
		gridRowHeight = rowHeight;
	}


	// KT9 fork: fixed uniform cell width for single-letter suggestions, so nothing shifts or scrunches
	// when moving the highlight between letters.
	public void setLetterMode(boolean yes, int cellWidth) {
		letterMode = yes;
		letterCellWidth = cellWidth;
	}


	public SuggestionsAdapter(Context context, @NonNull Consumer<Integer> onItemClick, @NonNull Consumer<Integer> onItemLongClick, int layout, int textViewResourceId, List<String> suggestions) {
		this.onItemClick = onItemClick;
		this.onItemLongClick = onItemLongClick;
		this.layout = layout;
		this.textViewResourceId = textViewResourceId;
		this.mInflater = LayoutInflater.from(context);
		this.mSuggestions = suggestions;
	}


	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		ViewHolder holder = new ViewHolder(mInflater.inflate(layout, parent, false));
		// KT9 fork: capture the style's (wide) horizontal padding before we ever override it.
		if (rowPaddingH < 0) {
			rowPaddingH = holder.suggestionItem.getPaddingLeft();
		}
		return holder;
	}


	@Override
	public void onBindViewHolder(ViewHolder holder, int position) {
		// KT9 fork: fill the cell in grid mode (so the glyph centers), wrap content in the normal row.
		ViewGroup.LayoutParams itemLp = holder.itemView.getLayoutParams();
		if (itemLp != null) {
			// Width: grid cell fills its column; letter cell is a fixed uniform width; word wraps content.
			int wantWidth = gridMode ? ViewGroup.LayoutParams.MATCH_PARENT
				: letterMode && letterCellWidth > 0 ? letterCellWidth
				: ViewGroup.LayoutParams.WRAP_CONTENT;
			// Grid: exact row height so glyphs center. Otherwise: fill the bar height (like stock).
			int wantHeight = gridMode && gridRowHeight > 0 ? gridRowHeight : ViewGroup.LayoutParams.MATCH_PARENT;
			// Only reassign when it actually changes (mode switch) — doing it on every rebind re-triggers
			// layout and makes the suggestions jump/squish while navigating.
			if (itemLp.width != wantWidth || itemLp.height != wantHeight) {
				itemLp.width = wantWidth;
				itemLp.height = wantHeight;
				holder.itemView.setLayoutParams(itemLp);
			}
		}

		// KT9 fork: minimal horizontal padding for fixed-width cells (grid + letters) so glyphs aren't
		// squeezed; the wide style padding only for variable-width word cells.
		if (rowPaddingH >= 0) {
			int gridPad = Math.round(holder.itemView.getResources().getDisplayMetrics().density); // ~1dp
			int wantPad = gridMode || letterMode ? gridPad : rowPaddingH;
			if (holder.suggestionItem.getPaddingLeft() != wantPad) {
				holder.suggestionItem.setPadding(wantPad, holder.suggestionItem.getPaddingTop(), wantPad, holder.suggestionItem.getPaddingBottom());
			}
		}

		// KT9 fork: render the punctuation-grid return key ("\n") as ↵ and the space (" ") as ␣.
		String raw = mSuggestions.get(position);
		String display = "\n".equals(raw) ? "↵" : " ".equals(raw) ? "␣" : raw;
		boolean wordMode = !gridMode && !letterMode;
		// KT9 fork: pad words with a thin space each side so the heavy font's overhanging glyphs
		// (y tail, bold w) aren't clipped at the text box, and the word stays centered.
		if (wordMode) {
			display = " " + display + " ";
		}
		SpannableString scaledText = new SpannableString(display);
		// KT9 fork: shrink only multi-letter word suggestions (the heavy brand font makes them run off
		// the edge); single letters and the punctuation grid stay full size.
		float sizeScale = wordMode ? textSize * 0.9f : textSize;
		scaledText.setSpan(new RelativeSizeSpan(sizeScale), 0, scaledText.length(), 0);
		holder.suggestionItem.setText(scaledText);

		holder.suggestionItem.setTag(position);
		holder.suggestionItem.setTextColor(selectedIndex == position ? colorHighlight : colorDefault);
		holder.suggestionItem.setBackgroundColor(selectedIndex == position ? backgroundHighlight : Color.TRANSPARENT);
		holder.suggestionItem.setOnClickListener(v -> onItemClick.accept((int) v.getTag()));
		holder.suggestionItem.setOnLongClickListener(v -> {
			onItemLongClick.accept((int) v.getTag());
			return true;
		});
	}


	@Override
	public int getItemCount() {
		return mSuggestions.size();
	}


	public void setSelection(int newIndex) {
		notifyItemChanged(selectedIndex);
		notifyItemChanged(selectedIndex = newIndex);
	}


	@SuppressLint("NotifyDataSetChanged")
	public void resetItems(int newIndex) {
		selectedIndex = newIndex;
		notifyDataSetChanged();
	}


	public void setTextSize(float size) {
		textSize = size;
	}


	public void setColorDefault(int colorDefault) {
		this.colorDefault = colorDefault;
	}


	public void setColorHighlight(int colorHighlight) {
		this.colorHighlight = colorHighlight;
	}


	public void setBackgroundHighlight(int backgroundHighlight) {
		this.backgroundHighlight = backgroundHighlight;
	}


	public class ViewHolder extends RecyclerView.ViewHolder {
		final TextView suggestionItem;

		ViewHolder(View itemView) {
			super(itemView);
			suggestionItem = itemView.findViewById(textViewResourceId);
		}
	}
}
