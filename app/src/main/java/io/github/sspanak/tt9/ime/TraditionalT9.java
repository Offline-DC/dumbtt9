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
		// KikaIME-style: on the hardware-keypad (tray/small) layout the INPUT view is empty — the visible
		// suggestion/mode bar lives in the CANDIDATES view (onCreateCandidatesView), which Android can
		// collapse to zero height on its own without hiding the window. On the large touch layouts the soft
		// keyboard genuinely IS the input view, so keep the original behavior there.
		if (settings != null && settings.isMainLayoutLarge()) {
			return buildBarView();
		}
		return getLayoutInflater().inflate(R.layout.input_view_empty, null);
	}


	@Override
	public View onCreateCandidatesView() {
		// Tray/small: the bar is the candidates view (toggled with setCandidatesViewShown). Large layouts
		// keep the bar in the input view, so they have no candidates view.
		if (settings != null && settings.isMainLayoutLarge()) {
			return null;
		}
		return buildBarView();
	}


	// Builds a fresh, parentless bar view. forceCreate() is required because the framework may re-create
	// the view and reusing the old one throws "IllegalStateException: The specified child already has a parent".
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

		// Large touch layouts: unchanged — cover the thin app gap above the keyboard when it is shown.
		if (settings != null && settings.isMainLayoutLarge()) {
			if (shouldBeVisible()) {
				outInsets.contentTopInsets = outInsets.visibleTopInsets + APP_GAP_COVER_PX;
			}
			return;
		}

		// Tray/small (KikaIME-style): the bar is the candidates view.
		// - When the bar is SHOWN, cover the thin white strip some apps (e.g. Messages) leave above it, by
		//   reporting the content region a few px into the bar's top edge. (super already set visibleTopInsets
		//   to the top of the visible candidates bar.)
		// - When the bar is HIDDEN, reserve NOTHING so the host app uses the full screen — otherwise a blank
		//   strip would linger where the bar was.
		if (trayBarShown) {
			outInsets.contentTopInsets = outInsets.visibleTopInsets + APP_GAP_COVER_PX;
		} else {
			final int full = getWindow() != null && getWindow().getWindow() != null
				? getWindow().getWindow().getDecorView().getHeight()
				: outInsets.visibleTopInsets;
			outInsets.contentTopInsets = full;
			outInsets.visibleTopInsets = full;
		}
	}


	// KT9 fork: bump this on every build so you can confirm from logcat which build is actually
	// running (grep for "KT9 build"). If the number here doesn't match, you're on a stale APK.
	public static final String KT9_BUILD = "KT9 build r23 — KikaIME structure: empty input view + bar in candidates view (setCandidatesViewShown)";

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
