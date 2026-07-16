package io.github.sspanak.tt9.ui.tray;

import android.content.Context;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.voice.VoiceInputOps;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.main.ResizableMainView;
import io.github.sspanak.tt9.ui.notifications.DictionaryLoadingBar;
import io.github.sspanak.tt9.util.Logger;

public class StatusBar {
	private boolean isShown = true;
	private double lastClickTime = 0;

	@NonNull private final ResizableMainView mainView;
	@Nullable private final TextView statusView;
	@NonNull private final SettingsStore settings;
	// KT9 fork: only ever holds transient messages (errors, dictionary loading, voice prompts,
	// command hints) now. The input mode is shown as a popup instead of a persistent "[ en ]" label,
	// so there is no mode text here. Empty string == nothing to show.
	@Nullable private String statusText = "";

	@NonNull private final DictionaryLoadingBar loadingBar;
	@NonNull private final Runnable onLoadingFinished;
	@NonNull private final Runnable onSwipe;


	public StatusBar(@NonNull Context context, @NonNull SettingsStore settings, @NonNull ResizableMainView mainView, @NonNull Runnable onDictionaryLoadingFinished, @NonNull Runnable onSwipe) {
		this.mainView = mainView;
		this.settings = settings;
		statusView = mainView.getView() != null ? mainView.getView().findViewById(R.id.status_bar) : null;
		if (statusView != null) {
			statusView.setOnTouchListener(this::onTouch);
		}

		loadingBar = DictionaryLoadingBar.getInstance(context);
		loadingBar.setOnStatusChange2(this::onLoading);
		onLoadingFinished = onDictionaryLoadingFinished;
		this.onSwipe = onSwipe;
	}


	/**
	 * Handle double-click and drag resizing
	 */
	private boolean onTouch(View v, MotionEvent event) {
		final int action = event.getActionMasked();

		if (!isShown) {
			if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_DOWN) {
				onSwipe.run();
			}
			return false;
		}

		switch (action) {
			case MotionEvent.ACTION_DOWN:
				mainView.onResizeStart(event.getRawY());
				return true;
			case MotionEvent.ACTION_MOVE:
				if (settings.getDragResize()) {
					mainView.onResizeThrottled(event.getRawY());
				}
				return true;
			case MotionEvent.ACTION_UP:
				long now = System.currentTimeMillis();
				if (settings.getDoubleTapResize() && now - lastClickTime < SettingsStore.SOFT_KEY_DOUBLE_CLICK_DELAY) {
					mainView.onSnap();
				} else if (settings.getDragResize()) {
					mainView.onResize(event.getRawY());
				}

				lastClickTime = now;

				return true;
		}

		return false;
	}


	public boolean isErrorShown() {
		return statusText != null && statusText.startsWith("❌");
	}


	public StatusBar setColorScheme() {
		if (statusView != null) {
			statusView.setTextColor(settings.getKeyboardTextColor());
		}
		return this;
	}


	public void setAccessibilityText(int stringResourceId) {
		if (statusView != null && stringResourceId != 0) {
			setAccessibilityText(statusView.getContext().getString(stringResourceId));
		}
	}


	public void setAccessibilityText(@Nullable String text) {
		if (statusView != null && text != null) {
			statusView.announceForAccessibility(text);
		}
	}


	public void setAccessibilityText(@NonNull InputMode inputMode) {
		if (statusView != null) {
			setAccessibilityText(inputMode.toAccessibilityString(statusView.getContext()));
		}
	}


	public void setAccessibilityTextCase(@NonNull InputMode inputMode) {
		if (statusView == null) {
			return;
		}

		String accessibilityText = switch (inputMode.getTextCase()) {
			case InputMode.CASE_LOWER -> statusView.getContext().getString(R.string.accessibility_text_case_lower);
			case InputMode.CASE_UPPER -> statusView.getContext().getString(R.string.accessibility_text_case_upper);
			case InputMode.CASE_CAPITALIZE -> statusView.getContext().getString(R.string.accessibility_text_case_capital);
			default -> null;
		};

		setAccessibilityText(accessibilityText);
	}


	public void setError(String error) {
		setAccessibilityText(error);
		setText("❌  " + error);
	}


	public void setText(int stringResourceId) {
		if (statusView != null && stringResourceId != 0) {
			setText(statusView.getContext().getString(stringResourceId));
		}
	}


	public void setText(String text) {
		statusText = text;
		this.render();
	}


	/**
	 * KT9 fork: the input mode (en / En / EN / 123 / TT9) is no longer drawn as a persistent
	 * "[ en ]" label in the tray. It is announced via a transient popup on every mode/case/language
	 * change (see TraditionalT9.showModePopup). This method is intentionally a no-op for the visible
	 * bar, so the many "refresh the mode label" callers no longer paint anything. Use clearText() to
	 * wipe a transient message and setError()/setText(...) to show one.
	 */
	public void setText(InputMode inputMode) {
		// no-op: mode is shown as a popup, not a persistent label
	}


	/**
	 * KT9 fork: clears any transient message, leaving the tray empty. Replaces the old habit of
	 * "resetting" the bar by re-drawing the mode label.
	 */
	public void clearText() {
		setText("");
	}


	/**
	 * KT9 fork: true when a transient message (error / loading / voice / command hint) is currently
	 * shown. Drives whether the tray keyboard needs to be visible on the tray layout.
	 */
	public boolean hasMessage() {
		return statusText != null && !statusText.isEmpty();
	}


	public void setText(VoiceInputOps voiceInputOps) {
		// KT9 fork: no [ ] brackets around the voice prompt ("speak now (press * when done)"); the
		// brackets are only for the short mode labels like [ en ] / [ TT9 ].
		setText(voiceInputOps.toString());
	}


	public void setShown(boolean yes) {
		if (isShown != yes) {
			isShown = yes;
			render();
		}
	}


	private void onLoading() {
		if (loadingBar.inProgress()) {
			setText("[ " + loadingBar.getShortMessage() + " ]");
		} else if (loadingBar.isCancelled() || loadingBar.isFailed()) {
			setError(loadingBar.getShortMessage());
		} else {
			onLoadingFinished.run();
		}
	}


	private void render() {
		if (statusView == null) {
			return;
		}

		if (statusText == null) {
			Logger.w("StatusBar.render", "Not displaying NULL status");
			return;
		}

		if (!isShown) {
			statusView.setText(null);
			return;
		}

		SpannableString scaledText = new SpannableString(statusText);
		scaledText.setSpan(new RelativeSizeSpan(settings.getSuggestionFontScale()), 0, statusText.length(), 0);

		statusView.setText(scaledText);
	}
}
