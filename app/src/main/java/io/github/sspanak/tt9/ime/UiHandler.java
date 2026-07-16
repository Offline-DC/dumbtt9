package io.github.sspanak.tt9.ime;

import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.hacks.AppHacks;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.ModePopup;
import io.github.sspanak.tt9.ui.StatusIcon;
import io.github.sspanak.tt9.ui.main.MainView;
import io.github.sspanak.tt9.ui.tray.StatusBar;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.sys.DeviceInfo;
import io.github.sspanak.tt9.util.sys.SystemSettings;

abstract class UiHandler extends AbstractHandler {
	private final static String LOG_TAG = "UiHandler";

	@NonNull protected final AppHacks appHacks = new AppHacks();
	protected SettingsStore settings;

	protected int displayTextCase = InputMode.CASE_UNDEFINED;
	protected boolean isMainViewShown = false;
	protected MainView mainView = null;
	@NonNull private final ModePopup modePopup = new ModePopup();

	// KT9 fork: re-entrancy guard. Toggling the input view can synchronously call back into
	// refreshTrayVisibility (updateInputViewShown → onFinishInputView → clear → setVisibility →
	// onContentChanged → refreshTrayVisibility). This flag drops those nested calls to prevent an
	// infinite recursion / StackOverflow.
	private boolean trayRefreshing = false;
	protected StatusBar statusBar = null;


	@Override
	public boolean onEvaluateInputViewShown() {
		super.onEvaluateInputViewShown();
		if (!SystemSettings.isTT9Selected(this)) {
			isMainViewShown = false;
			return false;
		}

		setInputField(getCurrentInputEditorInfo());
		// KikaIME-style: keep the bar shown whenever typing is possible — we never hide the window. The
		// suggestion strip only fills while composing (empty/thin otherwise), and the mode pill floats
		// above it. Because the pill is hosted by this always-shown window, it appears the same everywhere.
		return isMainViewShown = isTypingPossible();
	}


	/**
	 * KT9 fork: hook so the superclass can read the current input mode without depending on the
	 * subclass that owns it. Overridden in TypingHandler. Defaults to null ("unknown").
	 */
	protected InputMode getCurrentInputMode() {
		return null;
	}


	/**
	 * KT9 fork: whether the tray keyboard has anything worth showing. True in predictive (TT9) mode,
	 * while a special-character / punctuation panel is open, or while a transient message is showing.
	 * Fail-safe: any doubt resolves toward showing, never hiding.
	 */
	protected boolean trayHasContent() {
		final boolean palette = mainView != null && (mainView.isCommandPaletteShown() || mainView.isTextEditingPaletteShown());
		final boolean message = statusBar != null && statusBar.hasMessage();

		final InputMode mode = getCurrentInputMode();
		final boolean typing = mode != null && mode.isTyping();
		final boolean predictive = mode != null && InputModeKind.isPredictive(mode);
		final boolean panel = mode != null && mode.isSpecialCharPanelShown();

		// Show: palettes, transient messages, an open "*"/"1" special-char panel, or a predictive (TT9)
		// word actively being composed. We deliberately do NOT also require "has candidates right now":
		// the momentary empty frames between keystrokes would make the strip collapse and re-expand
		// (height flicker). It hides the instant composing stops (word accepted -> typing=false) and in
		// ABC/123 direct typing, which never shows a strip.
		return palette || message || panel || (predictive && typing);
	}


	/**
	 * KT9 fork: re-evaluate whether the thin-strip keyboard (tray / small) should be visible right
	 * now. Left entirely to the framework on the large touch layouts, whose keys are always shown.
	 */
	public void refreshTrayVisibility() {
		if (settings == null || settings.isMainLayoutLarge() || !SystemSettings.isTT9Selected(this)) {
			return;
		}
		if (trayRefreshing) {
			return;
		}

		trayRefreshing = true;
		try {
			// KT9 fork: keep the window's shown-state in sync with whether typing is currently possible.
			updateInputViewShown();
			// KT9 diagnostics: record the content decision + resulting geometry each refresh (tag KT9geo).
			logGeometry("refreshTray typingPossible=" + isTypingPossible() + " hasContent=" + trayHasContent());
		} finally {
			trayRefreshing = false;
		}
	}


	// --- KT9 diagnostics (tag: KT9geo) -----------------------------------------------------------------
	// Dumps the real on-screen geometry of the IME window, its decor view and tt9's bar, so we can see how
	// tall and where the keyboard window actually is on this device. This is the data needed to reproduce
	// how the reference (KikaIME) keyboard docks its bar at the bottom and collapses it: KikaIME uses a
	// FULL-SCREEN transparent candidates view with the bar pinned to the bottom (layout_alignParentBottom)
	// and onComputeInsets forcing contentTopInsets = screenHeight - barHeight (or full screenHeight when
	// idle). Compare BAR@y / DECOR height / WIN h against screen height in the logs.
	protected void logGeometry(String where) {
		try {
			final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
			String win = "n/a";
			android.view.View decor = null;
			if (getWindow() != null && getWindow().getWindow() != null) {
				final android.view.WindowManager.LayoutParams lp = getWindow().getWindow().getAttributes();
				win = "gravity=" + lp.gravity + " y=" + lp.y + " w=" + lp.width + " h=" + lp.height + " type=" + lp.type;
				decor = getWindow().getWindow().getDecorView();
			}
			final int[] dloc = new int[2]; int dw = -1, dh = -1;
			if (decor != null) { decor.getLocationOnScreen(dloc); dw = decor.getWidth(); dh = decor.getHeight(); }
			final int[] bloc = new int[2]; int bw = -1, bh = -1;
			final android.view.View bar = mainView != null ? mainView.getView() : null;
			if (bar != null) { bar.getLocationOnScreen(bloc); bw = bar.getWidth(); bh = bar.getHeight(); }
			Logger.d("KT9geo", where
				+ " | screen=" + dm.widthPixels + "x" + dm.heightPixels
				+ " fullscreen=" + isFullscreenMode() + " inputShown=" + isInputViewShown()
				+ " | WIN[" + win + "]"
				+ " | DECOR@y" + dloc[1] + " " + dw + "x" + dh
				+ " | BAR@y" + bloc[1] + " " + bw + "x" + bh);
		} catch (Exception e) {
			Logger.d("KT9geo", where + " ERR " + e.getMessage());
		}
	}


	/**
	 * KT9 fork: announce the current input mode (en / En / EN / 123 / TT9) with a transient popup,
	 * on the layouts that do not keep a persistent mode label. A single reused toast slot means rapid
	 * mode/case changes replace each other cleanly instead of stacking.
	 */
	public void showModePopup() {
		InputMode mode = getCurrentInputMode();
		if (mode != null && settings != null && settings.isModePopupEnabled()) {
			// The pill is anchored to the keyboard window, so it is only visible when that window is up.
			// Some hosts (e.g. the launcher home) dismiss the keyboard, leaving the window down — the pill
			// would then draw into a hidden window (isShowing() true, but nothing visible). Bring the window
			// up first so the pill always has a real host to float above.
			forceShowWindow();
			modePopup.show(this, mode.toString());
		}
	}


	@Override
	public boolean onEvaluateFullscreenMode() {
		return false;
	}


	@Override
	protected void onInit() {
		if (mainView == null) {
			mainView = new MainView(getFinalContext());
			initTray();
		} else {
			mainView.destroy();
			mainView.getView();
		}
	}


	protected void initTray() {
		mainView.getView();
		statusBar = new StatusBar(this, settings, mainView, this::resetStatus, () -> getSuggestionOps().cancelDelayedAccept());
		statusBar.setColorScheme();
		createSuggestionBar();
		getSuggestionOps().setColorScheme();
		// KT9 fork: createSuggestionBar() builds a fresh SuggestionOps, so re-sync the current mode
		// immediately, otherwise the options-row gating would be disabled until the next mode change.
		getSuggestionOps().setInputMode(getCurrentInputMode());
	}


	protected void initUi(InputMode inputMode) {
		if (mainView.create()) {
			initTray();
			setCurrentView();
		} else {
			getSuggestionOps().setColorScheme();
		}
		setStatusIcon(inputMode, getFinalContext().getLanguage());
		statusBar.setColorScheme().setText(inputMode);
		mainView.showKeyboard();
		mainView.render();

		SystemSettings.setNavigationBarBackground(getWindow().getWindow(), settings, mainView.isBackgroundBlendingEnabled());

		if (appHacks.isBrutalForceShowNeeded()) {
			brutalForceShowWindow();
		} else if (!isInputViewShown()) {
			updateInputViewShown();
		}

		// KT9 fork: apply the initial collapsed/expanded strip state for this field right away (e.g. start
		// collapsed in ABC/123 idle), so the bar never flashes at full height before the first key press.
		refreshTrayVisibility();
	}


	public void setCurrentView() {
		setInputView(onCreateInputView());
	}


	public int getDisplayTextCase(@Nullable Language language, int modeTextCase) {
		boolean hasUpperCase = language != null && language.hasUpperCase();
		if (!hasUpperCase) {
			return displayTextCase = InputMode.CASE_UNDEFINED;
		}

		if (modeTextCase == InputMode.CASE_UPPER) {
			return displayTextCase = InputMode.CASE_UPPER;
		}

		Text currentWord = new Text(language, getSuggestionOps().getCurrent());
		if (currentWord.isEmpty() || !currentWord.isAlphabetic()) {
			return displayTextCase = modeTextCase;
		}

		final int wordTextCase = currentWord.getTextCase();
		return displayTextCase = wordTextCase == InputMode.CASE_UPPER ? InputMode.CASE_CAPITALIZE : wordTextCase;
	}


	public void setStatusIcon(@Nullable InputMode mode, @Nullable Language language) {
		if (!settings.isStatusIconEnabled()) {
			return;
		}

		final int resId = new StatusIcon(settings.isStatusIconEnabled() ? mode : null, language, displayTextCase).resourceId;
		if (resId == 0) {
			hideStatusIcon();
		} else {
			showStatusIcon(resId);
		}
	}


	/**
	 * KT9 fork: whether typing is possible at all in the current field — i.e. not a passthrough field
	 * (calculators, etc.) and not the invisible stealth layout. The input view stays "shown" whenever
	 * this is true, so it inflates once and the strip can be collapsed/expanded by height alone.
	 */
	protected boolean isTypingPossible() {
		return determineInputModeId() != InputMode.MODE_PASSTHROUGH && !settings.isMainLayoutStealth();
	}


	protected boolean shouldBeVisible() {
		// KikaIME-match: the bar is shown whenever typing is possible in the current field (never gated on
		// "has content"). This lets forceShowWindow() bring the window up on demand — e.g. when a mode
		// changes on the launcher home, so the pill has a real window to float above — and keeps
		// onComputeInsets() in agreement.
		return isTypingPossible();
	}


	/**
	 * forceShowWindow
	 * Some applications may hide our window and it remains invisible until the screen is touched or OK is pressed.
	 * This is fine for touchscreen keyboards, but the hardware keyboard allows typing even when the window and the suggestions
	 * are invisible. This function forces the InputMethodManager to show our window.
	 * WARNING! Calling this may cause a restart, which will cause InputMode to be recreated. Depending
	 * on how much time the restart takes, this may erase the current user input.
	 */
	public void forceShowWindow() {
		if (isInputViewShown() || !shouldBeVisible()) {
			return;
		}

		if (DeviceInfo.AT_LEAST_ANDROID_9) {
			requestShowSelf(DeviceInfo.isSonimGen2(getApplicationContext()) ? 0 : InputMethodManager.SHOW_IMPLICIT);
		} else {
			showWindow(true);
		}
	}


	/**
	 * Shows the IME window using brutal force, ignoring IME flags and state, and any (invalid) app
	 * requests for passthrough mode. Note that this should not be randomly used, because it will
	 * cause the UI to appear in calculators, banking apps or others where it is not desired.
	 * Reported problems (in chronological order):
	 *	- <a href="https://github.com/sspanak/tt9/issues/920">Google search field in Firefox on Android 16</a>
	 *	- <a href="https://github.com/sspanak/tt9/issues/963">Gmail reply/forward on Android 16</a>
	 */
	private void brutalForceShowWindow() {
		if (!isShowInputRequested() || !isMainViewShown) {
			forceShowWindow();
		}

		if (!isShowInputRequested() || !isMainViewShown) {
			Logger.d(LOG_TAG, "InputMethodManager refused show request. Forcing visibility with showWindow().");
			showWindow(true);
		}
	}
}
