package io.github.sspanak.tt9.ime;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.github.sspanak.tt9.R;
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
		return getLayoutInflater().inflate(R.layout.input_view_empty, null);
	}


	@Override
	public View onCreateCandidatesView() {
		// Large layouts keep the bar in the input view -> no candidates view.
		if (settings != null && settings.isMainLayoutLarge()) {
			return null;
		}
		// Tray/small (KikaIME structure): a FULL-SCREEN, transparent host (candidate_host.xml is
		// match_parent x match_parent). tt9's bar goes into the bottom-pinned slot, so the window spans the
		// whole screen (so the mode pill always has a live host and nothing needs to shrink) but only the
		// bottom strip paints. The SLOT owns the bottom gravity, so tt9's own height management on the bar
		// view cannot knock it back to the top. The bar's visibility is toggled (GONE/VISIBLE) by
		// refreshTrayVisibility, and onComputeInsets reserves only its height, so when it is hidden the
		// screen is fully usable with no leftover black bar.
		final View host = getLayoutInflater().inflate(R.layout.candidate_host, null);
		final android.view.ViewGroup slot = host.findViewById(R.id.kt9_bar_slot);
		final View bar = buildBarView();
		if (bar.getParent() instanceof android.view.ViewGroup) {
			((android.view.ViewGroup) bar.getParent()).removeView(bar);
		}
		slot.addView(bar);
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
			// Tray/small (KikaIME structure): the candidates host is a full-screen transparent view, so the
			// framework would otherwise reserve the whole screen. Report screen-relative insets instead:
			// reserve ONLY the bar's height when it is shown, and nothing at all when it is hidden. This both
			// frees the screen for the app and (via visibleTopInsets) lets touches above the bar pass through.
			final int screenH = getResources().getDisplayMetrics().heightPixels;
			final int barH = trayBarShown ? currentBarHeightPx(screenH) : 0;
			final int top = Math.max(0, screenH - barH);
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
		final View decor = getWindow() != null && getWindow().getWindow() != null ? getWindow().getWindow().getDecorView() : null;
		if (decor != null) {
			decor.post(() -> logGeometry("onWindowShown"));
		} else {
			logGeometry("onWindowShown(no decor)");
		}
	}


	// KT9 fork: bump this on every build so you can confirm from logcat which build is actually
	// running (grep for "KT9 build"). If the number here doesn't match, you're on a stale APK.
	public static final String KT9_BUILD = "KT9 build r27 — bar now in a bottom-pinned slot inside the full-screen candidates host (fixes bar drawing at the top)";

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
