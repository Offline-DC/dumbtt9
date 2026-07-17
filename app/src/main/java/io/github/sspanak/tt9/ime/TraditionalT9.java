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
		// Tray/small — return tt9's bar DIRECTLY. The IME window is a WRAP strip docked at the bottom (gravity=80,
		// DECOR@y266 240x54 in the logs). A full-screen match_parent host does NOT help here: the framework's
		// candidates container wraps its content, so match_parent collapsed to the bar height and the window stayed
		// 54px (rootH=54) — proven in r54's WFRAME log. What actually drives the app push-up is onComputeInsets
		// reporting the bar's WINDOW-RELATIVE top (contentTopInsets is measured from the top of the IME window, NOT
		// the screen). With the window docked at the bottom, that top is ~0, which reserves the whole docked window
		// and makes the app resize up to the window's top edge. See onComputeInsets.
		final View bar = buildBarView();
		if (bar.getParent() instanceof android.view.ViewGroup) {
			((android.view.ViewGroup) bar.getParent()).removeView(bar);
		}
		// Start hidden unless there is real content right now, so a freshly-built candidates view never flashes
		// an empty bar during the window-show relayout (refreshTrayVisibility toggles it thereafter).
		trayBarShown = trayHasContent();
		bar.setVisibility(trayBarShown ? android.view.View.VISIBLE : android.view.View.GONE);

		trayHost = bar;        // diagnostics only (logGeometry reads its on-screen box)
		trayBarSlot = null;
		return bar;
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
			// Tray/small — THE push-up fix (r55). contentTopInsets/visibleTopInsets are measured from the TOP OF THE
			// IME WINDOW, not the screen (AOSP: "measured from the top of the input method window"). Our window is a
			// WRAP strip docked at the bottom (top ≈ y266). r53–r54 reported the bar's ON-SCREEN top (266), so the
			// framework placed the reserved region at windowTop+266 = y532 (off-screen) and concluded there was
			// nothing to avoid — the app never resized. r55 reports the bar's WINDOW-RELATIVE top instead
			// (getLocationInWindow, ≈0), so the reserved region is the whole docked window and the app resizes up to
			// the window's top edge (y266). When the bar is hidden we reserve nothing by reporting the full window
			// height (decor height), so the app stays full-screen.
			final View decor = (getWindow() != null && getWindow().getWindow() != null) ? getWindow().getWindow().getDecorView() : null;
			final int decorH = decor != null ? decor.getHeight() : 0;
			int top;
			if (trayBarShown) {
				final View bar = mainView != null ? mainView.getView() : null;
				int barTopInWindow = -1;
				if (bar != null && bar.getHeight() > 0 && bar.isShown()) {
					final int[] loc = new int[2];
					bar.getLocationInWindow(loc);
					barTopInWindow = Math.max(0, loc[1]);
					lastGoodInsetTop = barTopInWindow;
				}
				top = barTopInWindow >= 0 ? barTopInWindow : (lastGoodInsetTop >= 0 ? lastGoodInsetTop : 0);
			} else {
				// Bar hidden: contentTopInsets == the full window height means "no IME content", so no app resize.
				top = decorH > 0 ? decorH : 0;
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


	// KT9 fork (r47): current painted height of the bar (the suggestion strip). THE OLD screenH/6 (~53px)
	// FALLBACK WAS THE BLACK-BAND BUG: when onComputeInsets ran before the bar had measured (height 0), it
	// reserved 53px, telling the app the keyboard starts at y267 — but the bar actually paints ~24px at y296.
	// The app (adjust-resize) shrank to y267, and the 30px between y267 and the real bar had no app and a
	// transparent keyboard over it, so the black wallpaper showed through as a band. Fix: cache the last real
	// measured height and fall back to it (or a tight ~24px estimate), so we never over-reserve.
	private int lastGoodBarHeightPx = 0;
	// r53: last non-zero on-screen top of the bar, so onComputeInsets reserves the bar's real docked position even
	// on the pre-layout frame where getLocationOnScreen still reads 0.
	private int lastGoodInsetTop = 0;
	private int currentBarHeightPx(int screenH) {
		final View bar = mainView != null ? mainView.getView() : null;
		if (bar == null) {
			return lastGoodBarHeightPx > 0 ? lastGoodBarHeightPx : Math.round(screenH / 13f);
		}
		// KikaIME sizes its inset from the REAL content currently shown (toolbar height vs symbol-panel pages),
		// not a fixed guess. Do the same: force a fresh measure so the reserved height always matches what is
		// actually on screen this instant — the thin suggestion strip OR the taller special-char panel. Relying
		// on getHeight() reserved the stale strip height when the panel appeared, so the panel overlaid the app
		// instead of pushing it up. UNSPECIFIED height measures the view's natural (wrap) height.
		final int screenW = getResources().getDisplayMetrics().widthPixels;
		try {
			bar.measure(
				View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		} catch (Exception ignored) {}
		int h = bar.getMeasuredHeight();
		if (h <= 0) { h = bar.getHeight(); }
		if (h > 0) {
			lastGoodBarHeightPx = h;
			return h;
		}
		return lastGoodBarHeightPx > 0 ? lastGoodBarHeightPx : Math.round(screenH / 13f);
	}


	// r52: the USABLE display height (excludes the navigation bar), matching KikaIME's getDefaultDisplay().getSize().
	// getResources().getDisplayMetrics().heightPixels returns the FULL physical height on this device, which does
	// NOT subtract the nav bar the decor margin reserves — that mismatch was the 30px overlap. Falls back to the
	// display-metrics height if the WindowManager display is unavailable.
	private int usableScreenHeightPx() {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			final android.view.Display d = w != null ? w.getWindowManager().getDefaultDisplay() : null;
			if (d != null) {
				final android.graphics.Point p = new android.graphics.Point();
				d.getSize(p);
				if (p.y > 0) { return p.y; }
			}
		} catch (Exception ignored) {}
		return getResources().getDisplayMetrics().heightPixels;
	}

	// r52 diagnostic: compare the three "screen height" sources so we can see on-device whether this ROM reserves a
	// nav bar. If getRealSize.y > getSize.y, the difference IS the nav bar and the decor margin is correct.
	private void logDisplaySizes() {
		try {
			final int dm = getResources().getDisplayMetrics().heightPixels;
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			final android.view.Display d = w != null ? w.getWindowManager().getDefaultDisplay() : null;
			final android.graphics.Point size = new android.graphics.Point();
			final android.graphics.Point real = new android.graphics.Point();
			if (d != null) { d.getSize(size); d.getRealSize(real); }
			Logger.d("KT9geo", "DISPLAY displayMetrics=" + dm + " getSize=" + size.y + " getRealSize=" + real.y + " navBar=" + (real.y - size.y));
		} catch (Exception e) { Logger.d("KT9geo", "DISPLAY ERR " + e.getMessage()); }
	}


	private String lastInsetsLog = "";

	@Override
	public void onWindowShown() {
		super.onWindowShown();
		// r56: REMOVE the decor's 30px bottom margin. The DISPLAY log proves navBar=0 on this device
		// (getSize==getRealSize==320), so that margin is NOT a nav-bar reservation — it is an AppCompat/compileSdk
		// toolchain artifact (KikaIME, built against the old support lib, has none). Left in, it made the docked
		// window 54px while the bar is only 24px, so r55's window-relative inset reserved the full 54px and the app
		// pushed up 30px too far, leaving a dead strip below the bar (the "extra gap"). Zeroing it shrinks the window
		// to the bar's real height (24px, flush at the true screen bottom y320), so the app reserves exactly the bar.
		// installImeMarginFixer() is a pre-draw listener that re-zeroes it on any frame where the framework re-adds it.
		installImeMarginFixer();
		removeImeBottomMargin();
		logDisplaySizes();
		final View decor = getWindow() != null && getWindow().getWindow() != null ? getWindow().getWindow().getDecorView() : null;
		if (decor != null) {
			decor.post(() -> { removeImeBottomMargin(); logGeometry("onWindowShown"); logFrames("onWindowShown"); logWindowFlags("onWindowShown"); logWindowFrame("onWindowShown"); dumpImeTree(); });
		} else {
			logGeometry("onWindowShown(no decor)");
		}
	}


	// KT9 fork: the framework re-applies the 30px decor margin on every input-view show. This PRE-DRAW
	// listener zeroes it and, when it had to change it, CANCELS the current frame (returns false) so the 30px
	// gap is never actually drawn — a re-layout then draws the corrected frame. The cancel-streak guard
	// prevents any theoretical freeze if the framework ever re-applied the margin on every single pass.
	private View imeMarginListenerDecor = null;
	private int imeMarginCancelStreak = 0;
	private final android.view.ViewTreeObserver.OnPreDrawListener imeMarginFixer = () -> {
		if (removeImeBottomMargin() && imeMarginCancelStreak < 3) {
			if (imeMarginCancelStreak == 0) {
				// Logged once per burst: if this never appears, the clearFlags fix alone removed the margin and
				// the listener can be deleted. If it appears on every input-show, clearFlags is being re-set.
				Logger.d("KT9geo", "MARGIN LISTENER FIRED — clearFlags did not remove the 30px margin (listener still needed)");
			}
			imeMarginCancelStreak++;
			return false; // skip drawing this (gap) frame; re-layout with the corrected margin first
		}
		imeMarginCancelStreak = 0;
		return true;
	};

	// r43: prevent the decor from reserving system-bar space on its content (see onWindowShown). Consuming the
	// system-window insets means the content fills the full-screen (NO_LIMITS) surface from the FIRST layout,
	// so there is no 272->302 relayout and no transient dark bar. Event-driven (fires only on inset changes).
	// The transparent full-screen host makes drawing behind the status bar invisible, and onComputeInsets
	// still controls what the app avoids.
	private View decorInsetsConsumerTarget = null;
	private void installDecorInsetsConsumer() {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			final View decor = w != null ? w.getDecorView() : null;
			if (decor == null || decor == decorInsetsConsumerTarget) {
				return;
			}
			decor.setOnApplyWindowInsetsListener((v, insets) -> insets.consumeSystemWindowInsets());
			decorInsetsConsumerTarget = decor;
			decor.requestApplyInsets();
		} catch (Exception e) {
			Logger.d("KT9geo", "insetsConsumer ERR " + e.getMessage());
		}
	}


	private void installImeMarginFixer() {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			final View decor = w != null ? w.getDecorView() : null;
			if (decor == null || decor == imeMarginListenerDecor) {
				return; // already installed on this decor
			}
			if (imeMarginListenerDecor != null && imeMarginListenerDecor.getViewTreeObserver().isAlive()) {
				imeMarginListenerDecor.getViewTreeObserver().removeOnPreDrawListener(imeMarginFixer);
			}
			decor.getViewTreeObserver().addOnPreDrawListener(imeMarginFixer);
			imeMarginListenerDecor = decor;
		} catch (Exception e) {
			Logger.d("KT9geo", "installImeMarginFixer ERR " + e.getMessage());
		}
	}


	// KT9 fork: zero the bottom margin the framework puts on the IME decor's content wrapper (see comment in
	// onWindowShown). Iterates the decor's direct children so it is robust to which child carries the margin.
	private boolean removeImeBottomMargin() {
		boolean changed = false;
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			if (w == null || !(w.getDecorView() instanceof android.view.ViewGroup)) {
				return false;
			}
			final android.view.ViewGroup decor = (android.view.ViewGroup) w.getDecorView();
			for (int i = 0; i < decor.getChildCount(); i++) {
				final View child = decor.getChildAt(i);
				final android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
				if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
					final android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
					if (mlp.bottomMargin != 0) {
						mlp.bottomMargin = 0;
						child.setLayoutParams(mlp);
						changed = true;
					}
				}
			}
		} catch (Exception e) {
			Logger.d("KT9geo", "removeImeBottomMargin ERR " + e.getMessage());
		}
		return changed;
	}


	// KT9 diagnostics: log the IME window flags + decor background. This is the decisive test for the theme
	// fix — if drawsSysBars=false and decorBg is a transparent color, TTheme.Ime applied. If drawsSysBars is
	// still true, the theme did NOT reach the window. Grep logcat for "KT9geo" -> "FLAGS".
	private String lastFlagsLog = "";
	private void logWindowFlags(String where) {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			if (w == null) { return; }
			final android.view.WindowManager.LayoutParams lp = w.getAttributes();
			final boolean drawsBars = (lp.flags & android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
			final View decor = w.getDecorView();
			final android.graphics.drawable.Drawable bg = decor != null ? decor.getBackground() : null;
			String bgStr = bg == null ? "null" : bg.getClass().getSimpleName();
			if (bg instanceof android.graphics.drawable.ColorDrawable) {
				bgStr += String.format("#%08X", ((android.graphics.drawable.ColorDrawable) bg).getColor());
			}
			final String line = "FLAGS " + where + " | winFlags=0x" + Integer.toHexString(lp.flags)
				+ " softInput=0x" + Integer.toHexString(lp.softInputMode)
				+ " drawsSysBars=" + drawsBars + " decorBg=" + bgStr;
			if (!line.equals(lastFlagsLog)) { lastFlagsLog = line; Logger.d("KT9geo", line); }
		} catch (Exception e) {
			Logger.d("KT9geo", "FLAGS ERR " + e.getMessage());
		}
	}


	// KT9 diagnostics: the REAL on-screen window rectangle via getWindowVisibleDisplayFrame (what KikaIME
	// itself queries). This is the ground truth the view-position logs lacked: if the window surface reserves
	// the phantom nav bar, `visibleDisplayFrame` bottom is ~290; if FLAG_LAYOUT_NO_LIMITS worked it is 320.
	private String lastFrameLog = "";
	private void logWindowFrame(String where) {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			final View decor = w != null ? w.getDecorView() : null;
			if (decor == null) { return; }
			final android.graphics.Rect vis = new android.graphics.Rect();
			decor.getWindowVisibleDisplayFrame(vis);
			final int[] loc = new int[2];
			decor.getLocationOnScreen(loc);
			final String line = "WFRAME " + where + " | visibleDisplayFrame=" + vis.toShortString()
				+ " decorOnScreen=[" + loc[0] + "," + loc[1] + " " + decor.getWidth() + "x" + decor.getHeight() + "]"
				+ " rootH=" + decor.getRootView().getHeight();
			if (!line.equals(lastFrameLog)) { lastFrameLog = line; Logger.d("KT9geo", line); }
		} catch (Exception e) {
			Logger.d("KT9geo", "WFRAME ERR " + e.getMessage());
		}
	}


	// KT9 diagnostics: dump the IME decor's view tree (bounds, padding, bottom-margin) to a shallow depth, to
	// locate exactly which view holds the ~30px between the candidate frame (y290) and the true bottom (y320).
	// Grep logcat for "KT9geo" and read the "TREE" block.
	private void dumpImeTree() {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			if (w == null) { Logger.d("KT9geo", "TREE: no window"); return; }
			final StringBuilder sb = new StringBuilder("TREE (decor subtree, depth<=4):");
			dumpTree(w.getDecorView(), 0, sb);
			Logger.d("KT9geo", sb.toString());
		} catch (Exception e) {
			Logger.d("KT9geo", "TREE ERR " + e.getMessage());
		}
	}

	private void dumpTree(View v, int depth, StringBuilder sb) {
		if (v == null || depth > 4) {
			return;
		}
		final int[] l = new int[2];
		v.getLocationOnScreen(l);
		sb.append("\n");
		for (int i = 0; i < depth; i++) { sb.append("  "); }
		sb.append(v.getClass().getSimpleName());
		try {
			if (v.getId() != View.NO_ID) { sb.append("#").append(getResources().getResourceEntryName(v.getId())); }
		} catch (Exception ignored) {}
		sb.append(" @y").append(l[1]).append(" ").append(v.getWidth()).append("x").append(v.getHeight())
			.append(" padTB=").append(v.getPaddingTop()).append("/").append(v.getPaddingBottom())
			.append(" mB=").append(marginBottom(v))
			.append(" vis=").append(v.getVisibility());
		if (v instanceof android.view.ViewGroup) {
			final android.view.ViewGroup vg = (android.view.ViewGroup) v;
			for (int i = 0; i < vg.getChildCount(); i++) {
				dumpTree(vg.getChildAt(i), depth + 1, sb);
			}
		}
	}

	private int marginBottom(View v) {
		final android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
		return lp instanceof android.view.ViewGroup.MarginLayoutParams ? ((android.view.ViewGroup.MarginLayoutParams) lp).bottomMargin : -1;
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
	public static final String KT9_BUILD = "KT9 build r56 — kill the extra gap. r55 nailed the push-up (window-relative inset), but the docked window was 54px (24px bar + 30px decor margin) so the app reserved the full 54px and pushed up 30px too far, leaving a dead strip below the bar. The DISPLAY log proves navBar=0, so that 30px is a toolchain artifact (AppCompat/compileSdk), not nav-bar space — r56 zeroes it (installImeMarginFixer + removeImeBottomMargin), shrinking the window to the bar's true 24px, flush at the bottom, so the app reserves exactly the bar. Prior note kept for history — r55 was: THE actual push-up fix (coordinate space). r54's WFRAME log was decisive: decorOnScreen=[0,266 240x54] rootH=54 — the IME window is a 54px strip docked at the bottom, and a match_parent host does NOT expand it (the framework's candidates container wraps content). The real bug: contentTopInsets is measured FROM THE TOP OF THE IME WINDOW, not the screen. I was reporting the bar's ON-SCREEN top (266), so the framework placed the reserved region at windowTop(266)+266 = y532, off-screen, and resized nothing — every prior build failed for this one reason. r55 reports the bar's WINDOW-RELATIVE top (getLocationInWindow, ≈0), so the reserved region is the whole docked window and the app finally resizes up to y266. Reverted the dead full-screen host. NOTE: because the decor still carries a 30px bottom margin, expect the bar to sit ~30px above the very bottom with a thin strip below it — that is now a safe one-line follow-up (navBar=0).";

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
