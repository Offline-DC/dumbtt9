package io.github.sspanak.tt9.ime;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.github.sspanak.tt9.db.DataStore;
import io.github.sspanak.tt9.db.words.DictionaryLoader;
import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.UI;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.SupremeExecutor;
import io.github.sspanak.tt9.util.sys.DeviceInfo;
import io.github.sspanak.tt9.util.sys.SystemSettings;

public class TraditionalT9 extends PremiumHandler {
	private static final String LOG_TAG = "MAIN";

	// KT9 fork: how many px the app is allowed to extend behind the keyboard's top edge, to hide the
	// thin white gap some apps leave above the keyboard. Tune this if a sliver remains or too much of the
	// text box is hidden.
	private static final int APP_GAP_COVER_PX = 8;

	private Future<?> asyncInitThread;

	@NonNull private final Handler backgroundTasks = new Handler(Looper.getMainLooper());
	@NonNull private final Handler zombieDetector = new Handler(Looper.getMainLooper());
	@NonNull private final Handler heartbeatDetector = new Handler(Looper.getMainLooper());
	private boolean isDead = false;
	private int zombieChecks = 0;

	// A String to be committed after successfully starting in an input field.
	@NonNull private final StringBuffer onAfterStartText = new StringBuffer();


	@Override
	public View onCreateInputView() {
		// KikaIME structure: on the hardware-keypad (tray/small) layout the INPUT view is empty; the visible
		// bar lives in the CANDIDATES view (a full-screen transparent host, see onCreateCandidatesView). On
		// the large touch layouts the soft keyboard genuinely IS the input view, so keep it there.
		if (settings != null && settings.isMainLayoutLarge()) {
			return buildBarView();
		}
		// Tray/small: a genuinely zero-height input view, so the full-screen candidates host gets the entire
		// window and the bar can reach the true screen bottom. setInputView() forces MATCH_PARENT on whatever
		// we return, so override onMeasure to report 0 height regardless.
		return new View(this) {
			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
				setMeasuredDimension(0, 0);
			}
		};
	}


	@Override
	public View onCreateCandidatesView() {
		// Large layouts keep the bar in the input view -> no candidates view.
		if (settings != null && settings.isMainLayoutLarge()) {
			return null;
		}
		// Tray/small (KikaIME structure): a FULL-SCREEN, transparent host. tt9's bar goes into a bottom-pinned
		// slot, so the window spans the whole screen (the mode pill always has a live host and nothing needs
		// to shrink) but only the bottom strip paints. The bar's visibility is toggled (GONE/VISIBLE) by
		// refreshTrayVisibility, and onComputeInsets reserves only its height, so an idle screen has no black
		// bar. Built in code with EXPLICIT match_parent params: inflating an XML root with a null parent
		// silently drops its layout_width/height, which collapsed the host to bar-height (the r27 bug) and
		// left the bar at the top.
		final android.widget.FrameLayout host = new android.widget.FrameLayout(this);
		host.setLayoutParams(new android.view.ViewGroup.LayoutParams(
			android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
		host.setBackgroundColor(android.graphics.Color.TRANSPARENT);

		// Filler: a transparent match_parent x match_parent child forces the host to full-screen height even
		// if the framework re-adds the candidates view as wrap_content (a wrap_content parent measures a
		// match_parent child to the full available height). Without a full-height host, a bottom-gravity
		// child has no room to drop and the bar stays at the top.
		final View filler = new View(this);
		host.addView(filler, new android.widget.FrameLayout.LayoutParams(
			android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

		// Bottom-pinned slot that WE own. tt9 only manages the bar INSIDE it, so tt9's height code can't
		// strip the bar's gravity and bounce it to the top (that was the r26 failure) — the slot keeps the
		// bottom gravity no matter what tt9 does to the bar.
		final android.widget.FrameLayout slot = new android.widget.FrameLayout(this);
		final android.widget.FrameLayout.LayoutParams slotLp = new android.widget.FrameLayout.LayoutParams(
			android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
		slotLp.gravity = android.view.Gravity.BOTTOM;

		final View bar = buildBarView();
		if (bar.getParent() instanceof android.view.ViewGroup) {
			((android.view.ViewGroup) bar.getParent()).removeView(bar);
		}
		slot.addView(bar, new android.widget.FrameLayout.LayoutParams(
			android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
		host.addView(slot, slotLp);

		trayHost = host;
		trayBarSlot = slot;
		return host;
	}


	// Builds tt9's bar (status + suggestions) as a fresh, parentless view. forceCreate() is required
	// because the framework may re-create the view and reusing the old one throws "already has a parent".
	private View buildBarView() {
		mainView.forceCreate();
		initTray();
		statusBar.setText(mInputMode);
		suggestionOps.set(mInputMode.getSuggestions(), mInputMode.containsGeneratedSuggestions());
		mainView.render();
		return mainView.getView();
	}


	@Override
	public void onComputeInsets(Insets outInsets) {
		super.onComputeInsets(outInsets);

		if (settings != null && !settings.isMainLayoutLarge()) {
			// Tray/small (KikaIME structure): the candidates host is a full-screen transparent view. Reserve
			// for the app exactly the region from the bar's REAL top edge downward (so the app never draws
			// under the bar), and nothing when the bar is hidden. Using the bar's measured on-screen position —
			// rather than screenHeight - barHeight — keeps the app aligned with the bar even when the window
			// doesn't reach the very bottom of the display (e.g. a navigation-bar gap).
			final int screenH = getResources().getDisplayMetrics().heightPixels;
			int top = screenH;
			if (trayBarShown) {
				final View bar = mainView != null ? mainView.getView() : null;
				if (bar != null && bar.getHeight() > 0) {
					final int[] barLoc = new int[2];
					bar.getLocationOnScreen(barLoc);
					top = Math.max(0, barLoc[1]);
				} else {
					top = Math.max(0, screenH - currentBarHeightPx(screenH));
				}
			}
			outInsets.contentTopInsets = top;
			outInsets.visibleTopInsets = top;
		} else if (shouldBeVisible()) {
			// Large touch layouts: unchanged — cover the thin app gap above the keyboard when it is shown.
			outInsets.contentTopInsets = outInsets.visibleTopInsets + APP_GAP_COVER_PX;
		}

		// KT9 diagnostics: log the insets handed to the host app, but only when they change (this runs every
		// frame). content/visible are measured from the TOP of the screen. Grep logcat for "KT9geo".
		final String insetsLine = "insets content=" + outInsets.contentTopInsets + " visible=" + outInsets.visibleTopInsets + " touchable=" + outInsets.touchableInsets + " trayBarShown=" + trayBarShown;
		if (!insetsLine.equals(lastInsetsLog)) {
			lastInsetsLog = insetsLine;
			Logger.d("KT9geo", insetsLine);
		}
	}


	// KT9 fork: current painted height of the bar (status + suggestions). Uses the measured view height;
	// falls back to ~1/6 screen before the first layout so we never reserve a wild value.
	private int currentBarHeightPx(int screenH) {
		final View bar = mainView != null ? mainView.getView() : null;
		final int h = bar != null ? bar.getHeight() : 0;
		return h > 0 ? h : Math.round(screenH / 6f);
	}


	private String lastInsetsLog = "";

	@Override
	public void onWindowShown() {
		super.onWindowShown();
		// KT9 fork (tray/small): extend the IME window's content into the bottom system-inset (navigation-bar)
		// area, so the full-screen candidates host reaches the TRUE screen bottom. Otherwise the candidate
		// frame is inset ~30px and the bar floats above a black strip on apps without a nav bar. The LAYOUT_*
		// flags only change layout — they do NOT hide the nav bar.
		if (settings != null && !settings.isMainLayoutLarge() && getWindow() != null && getWindow().getWindow() != null) {
			getWindow().getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
		}
		final View decor = getWindow() != null && getWindow().getWindow() != null ? getWindow().getWindow().getDecorView() : null;
		if (decor != null) {
			decor.post(() -> { logGeometry("onWindowShown"); logFrames("onWindowShown"); });
		} else {
			logGeometry("onWindowShown(no decor)");
		}
	}


	// KT9 diagnostics: dump the IME's input & candidate frames plus the system-bar insets, so we can identify
	// exactly what the ~30px gap below the bar is (nav-bar inset vs input frame vs candidate-frame inset).
	// Grep logcat for "KT9geo FRAMES".
	private void logFrames(String where) {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			if (w == null) { Logger.d("KT9geo", where + " FRAMES: no window"); return; }
			String insetsStr = "n/a";
			final android.view.WindowInsets rwi = w.getDecorView().getRootWindowInsets();
			if (rwi != null) {
				insetsStr = "sysTop=" + rwi.getSystemWindowInsetTop() + " sysBottom=" + rwi.getSystemWindowInsetBottom() + " stableBottom=" + rwi.getStableInsetBottom();
			}
			final View inputArea = w.findViewById(android.R.id.inputArea);
			final View candFrame = (trayHost != null && trayHost.getParent() instanceof View) ? (View) trayHost.getParent() : null;
			Logger.d("KT9geo", where + " FRAMES | sysUiVis=0x" + Integer.toHexString(w.getDecorView().getSystemUiVisibility())
				+ " | insets[" + insetsStr + "] | inputArea " + frameStr(inputArea) + " | candFrame " + frameStr(candFrame));
		} catch (Exception e) {
			Logger.d("KT9geo", where + " FRAMES ERR " + e.getMessage());
		}
	}

	private String frameStr(View v) {
		if (v == null) {
			return "null";
		}
		final int[] loc = new int[2];
		v.getLocationOnScreen(loc);
		return "@y" + loc[1] + " " + v.getWidth() + "x" + v.getHeight() + " vis=" + v.getVisibility();
	}


	// KT9 fork: bump this on every build so you can confirm from logcat which build is actually
	// running (grep for "KT9 build"). If the number here doesn't match, you're on a stale APK.
	public static final String KT9_BUILD = "KT9 build r30 — extend window through bottom nav-bar inset (bar to true bottom); + FRAMES diagnostics (input/candidate frames, sys insets)";

	@Override
	public void onStartInput(EditorInfo inputField, boolean restarting) {
		Logger.i(LOG_TAG, "===> " + KT9_BUILD);
		Logger.i(
			LOG_TAG,
			"===> Start Up; packageName: " + inputField.packageName + " inputType: " + inputField.inputType + " actionId: " + inputField.actionId + " imeOptions: " + inputField.imeOptions + " privateImeOptions: " + inputField.privateImeOptions + " extras: " + inputField.extras
		);
		onStart(inputField, restarting);
	}


	@Override
	public void onStartInputView(EditorInfo inputField, boolean restarting) {
		onStart(inputField, restarting);
	}


	@Override
	public void onFinishInputView(boolean finishingInput) {
		super.onFinishInputView(finishingInput);
		onFinishTyping(finishingInput);
	}


	@Override
	public void onFinishInput() {
		super.onFinishInput();
		onStop();
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int result = super.onStartCommand(intent, flags, startId);

		final String command = intent != null ? intent.getStringExtra(UI.COMMAND) : null;

		switch (command == null ? "" : command) {
			case UI.COMMAND_WAKEUP_MAIN -> forceShowWindow();
			case UI.COMMAND_PRINT_VOICE_INPUT -> {
				final String text = intent.getStringExtra(UI.COMMAND_PRINT_VOICE_INPUT_TEXT);
				if (text != null) {
					onAfterStartText.append(text);
				}
			}
		}

		return result;
	}


	@Override
	protected void onInit() {
		isDead = false;
		zombieChecks = 0;
		settings.setDemoMode(false);
		Logger.setLevel(settings.getLogLevel());

		asyncInitThread = asyncInitThread == null ? SupremeExecutor.submit(this::runHeavyInitTasks) : asyncInitThread;

		super.onInit();
	}


	@Override
	protected boolean onStart(EditorInfo field, boolean restarting) {
		Logger.setLevel(settings.getLogLevel());

		if (SystemSettings.isTT9Selected(this)) {
			startHeartbeatCheck();
		} else {
			if (zombieChecks == 0) {
				startZombieCheck();
			}
			return false;
		}

		try {
			if (asyncInitThread != null && !asyncInitThread.isCancelled() && !asyncInitThread.isDone()) {
				asyncInitThread.get();
			}
		} catch (InterruptedException | ExecutionException e) {
			Logger.w(LOG_TAG, "Async initialization failed. " + e.getMessage() + ". Retrying on main thread.");
			runHeavyInitTasks();
		} finally {
			asyncInitThread = null;
		}

		appHacks.onBeforeStart(this, settings, mLanguage, field, mInputMode, suggestionOps, restarting);

		if (isDead || !super.onStart(field, restarting)) {
			getDisplayTextCase();
			setStatusIcon(mInputMode, mLanguage);
			return false;
		}

		if (InputModeKind.isPassthrough(mInputMode)) {
			onStop();
		}	else {
			backgroundTasks.removeCallbacksAndMessages(null);
			initUi(mInputMode);
		}

		onAfterStart(field);

		return true;
	}


	private void onAfterStart(EditorInfo field) {
		final InputType newInputType = new InputType(this, field);

		if (newInputType.isText()) {
			DataStore.loadWordPairs(LanguageCollection.getAll(settings.getEnabledLanguageIds()));
		}

		if (!newInputType.isUs()) {
			DictionaryLoader.autoLoad(this, settings, mLanguage);
		}

		if (onAfterStartText.length() > 0) {
			onText(onAfterStartText.toString(), false);
			onAfterStartText.setLength(0);
		}
	}


	@Override
	protected void onStop() {
		stopVoiceInput();
		onFinishTyping(true);
		statusBar.setText(mInputMode);

		if (isInputViewShown()) {
			updateInputViewShown();
		}

		if (SystemSettings.isTT9Selected(this)) {
			backgroundTasks.removeCallbacksAndMessages(null);
			backgroundTasks.postDelayed(this::runBackgroundTasks, SettingsStore.WORD_BACKGROUND_TASKS_DELAY);
		}

		if (zombieChecks == 0) {
			startZombieCheck();
		}

		stopHeartbeatCheck();
	}


	@Override
	protected void onFinishTyping(boolean willExitInput) {
		super.onFinishTyping(willExitInput);
		getDisplayTextCase();
		setStatusIcon(mInputMode, mLanguage);
	}


	/**
	 * On Android 11+ onStop() and onDestroy() are sometimes not called when the user switches to a
	 * different IME. Here we attempt to detect if we are disabled, then hide and kill ourselves.
	 */
	private void startHeartbeatCheck() {
		if (!SystemSettings.isTT9Selected(this)) {
			onZombie();
		} else if (!isDead && !InputModeKind.isPassthrough(mInputMode)) {
			heartbeatDetector.postDelayed(this::startHeartbeatCheck, SettingsStore.ZOMBIE_HEARTBEAT_INTERVAL);
			Logger.v(LOG_TAG, "===> Heart is beating");
		}
	}


	private void stopHeartbeatCheck() {
		if (!DeviceInfo.AT_LEAST_ANDROID_10 || heartbeatDetector.hasCallbacks(this::startHeartbeatCheck)) {
			heartbeatDetector.removeCallbacksAndMessages(null);
			Logger.d(LOG_TAG, "===> Heartbeat check stopped");
		}
	}


	/**
	 * Similar to the heartbeat check, but detects if we are on when invisible or after re-init.
	 */
	private void startZombieCheck() {
		if (zombieChecks > 0 && !SystemSettings.isTT9Selected(this)) {
			zombieChecks = 0;
			onZombie();
			return;
		}

		if (!isDead && ++zombieChecks < SettingsStore.ZOMBIE_CHECK_MAX) {
			zombieDetector.postDelayed(this::startZombieCheck, SettingsStore.ZOMBIE_CHECK_INTERVAL);
		} else {
			Logger.d(LOG_TAG, "Not a zombie after " + zombieChecks + " checks");
			zombieChecks = 0;
		}
	}


	private void onZombie() {
		if (isDead) {
			Logger.w(LOG_TAG, "===> Already dead. Cannot kill self.");
			return;
		}

		Logger.w(LOG_TAG, "===> Killing self");
		requestHideSelf(0);
		cleanUp();
		stopSelf();
		isDead = true;
	}


	@Override
	protected void cleanUp() {
		stopHeartbeatCheck();
		zombieDetector.removeCallbacksAndMessages(null);
		zombieChecks = SettingsStore.ZOMBIE_CHECK_MAX;
		backgroundTasks.removeCallbacksAndMessages(null);
		super.cleanUp();
		setInputField(null);
		Logger.d(LOG_TAG, "===> Final cleanup completed");
	}


	@Override
	public void onDestroy() {
		if (isDead) {
			Logger.w(LOG_TAG, "===> Already dead. Not destroying self.");
			return;
		}

		cleanUp();
		isDead = true;

		try {
			super.onDestroy();
		} catch (Exception e) {
			if (mainView != null && mainView.getView() != null) {
				Logger.e(LOG_TAG, "===> MainView destroy failed: " + e.getMessage() + ". Destroying manually.");
				mainView.destroy();
			}
		}

		Logger.d(LOG_TAG, "===> Shutdown completed");
	}


	@Override
	public void onTimeout(int startId) {
		onZombie();
		super.onTimeout(startId);
	}


	@Override
	protected boolean onNumber(int key, boolean hold, int repeat) {
		if (InputModeKind.isPredictive(mInputMode) && DictionaryLoader.autoLoad(this, settings, mLanguage)) {
			return true;
		}
		return super.onNumber(key, hold, repeat);
	}


	@Override
	protected TraditionalT9 getFinalContext() {
		return this;
	}


	private void runHeavyInitTasks() {
		LanguageCollection.init(getApplicationContext());
		DataStore.init(getApplicationContext());
		mindReader.init(getApplicationContext()); // create the database tables, if they don't exist already
		Logger.d(LOG_TAG, "Heavy initialization tasks completed successfully");
	}


	private void runBackgroundTasks() {
		SupremeExecutor.submit(() -> {
			mindReader.persist();
			voiceInputOps.forceAlternativeInput(false).enableOfflineMode();
			if (!DictionaryLoader.isRunning()) {
				DataStore.saveWordPairs();
				DataStore.normalizeNext();
			}
		});
	}
}
