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
			// Tray/small. contentTopInsets/visibleTopInsets are window-relative (AOSP: "measured from the top of the
			// input method window"); the window is a WRAP strip docked at the bottom (top ≈ y266). r55 fixed the
			// push-up by reserving the docked window. r61 fixes the GAP the ROM's baked-in bottom margin leaves:
			// this TCL device injects an UNREMOVABLE `margin`px (30) bottom margin into the IME decor — r57–r60 tried
			// every window flag (drawsSysBars, setDecorFitsSystemWindows(false), LAYOUT_NO_LIMITS, systemUiVisibility,
			// consuming insets) and mB stayed 30 with sysBottom=0, proving it is not inset-derived. So we WORK AROUND
			// it: reserve only the bar's height (contentTopInsets = margin -> the app avoids just the bottom barHeight
			// and no longer over-pushes by 30px), and shift the bar DOWN by `margin` (applyFlushBarShift) so it draws
			// flush at the true screen bottom rather than margin px above it.
			final View decor = (getWindow() != null && getWindow().getWindow() != null) ? getWindow().getWindow().getDecorView() : null;
			final int decorH = decor != null ? decor.getHeight() : 0;
			final int margin = imeBottomMarginPx();
			int top;
			if (trayBarShown) {
				top = margin;                // reserve (windowHeight - margin) = the bar's height, at the very bottom
				applyFlushBarShift(margin);  // and move the bar into that strip so it renders flush at the bottom
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
		final String insetsLine = "insets content=" + outInsets.contentTopInsets + " visible=" + outInsets.visibleTopInsets + " touchable=" + outInsets.touchableInsets + " trayBarShown=" + trayBarShown + " margin=" + lastGoodMarginPx;
		if (!insetsLine.equals(lastInsetsLog)) {
			lastInsetsLog = insetsLine;
			Logger.d("KT9geo", insetsLine);
		}
	}


	// r61: the IME decor's baked-in bottom margin (30px on this TCL ROM), read live so we adapt to whatever it is.
	// No window API removes it (r57–r60 all failed), so onComputeInsets reserves only the bar height and we shift
	// the bar into that strip. Falls back to the last non-zero value while the decor is mid-relayout.
	private int lastGoodMarginPx = 0;
	private int imeBottomMarginPx() {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			if (w != null && w.getDecorView() instanceof android.view.ViewGroup) {
				final android.view.ViewGroup decor = (android.view.ViewGroup) w.getDecorView();
				for (int i = 0; i < decor.getChildCount(); i++) {
					final android.view.ViewGroup.LayoutParams lp = decor.getChildAt(i).getLayoutParams();
					if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
						final int bm = ((android.view.ViewGroup.MarginLayoutParams) lp).bottomMargin;
						if (bm > 0) { lastGoodMarginPx = bm; return bm; }
					}
				}
			}
		} catch (Exception ignored) {}
		return lastGoodMarginPx;
	}

	// r61: shift tt9's bar DOWN by the ROM margin so it renders flush at the true screen bottom, and disable clipping
	// on the whole IME decor subtree so the shifted bar is not cut off by the short (barHeight-tall) content wrapper
	// it lives inside. Cheap: the IME decor subtree is ~7 views. Idempotent.
	private void applyFlushBarShift(int margin) {
		try {
			final android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
			final View decor = w != null ? w.getDecorView() : null;
			if (decor != null) { setNoClipRecursive(decor); }
			final View bar = mainView != null ? mainView.getView() : null;
			if (bar != null && bar.getTranslationY() != margin) { bar.setTranslationY(margin); }
		} catch (Exception ignored) {}
	}
	private void setNoClipRecursive(View v) {
		if (v instanceof android.view.ViewGroup) {
			final android.view.ViewGroup vg = (android.view.ViewGroup) v;
			vg.setClipChildren(false);
			vg.setClipToPadding(false);
			for (int i = 0; i < vg.getChildCount(); i++) {
				setNoClipRecursive(vg.getChildAt(i));
			}
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
		// r57: the 30px strip is reserved because the IME window carries FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS (the FLAGS
		// log shows drawsSysBars=true even though ImeWindowTheme sets windowDrawsSystemBarBackgrounds=false — the
		// theme attr is ignored for IME windows on this ROM). That flag is now cleared in onConfigureWindow, which
		// runs BEFORE each layout, so the strip is never reserved — stable, no flicker. r56's runtime margin removal
		// is gone: it fought the framework every frame and made the window oscillate 54px<->24px (the black band).
		logDisplaySizes();
		final View decor = getWindow() != null && getWindow().getWindow() != null ? getWindow().getWindow().getDecorView() : null;
		if (decor != null) {
			decor.post(() -> { logGeometry("onWindowShown"); logFrames("onWindowShown"); logWindowFlags("onWindowShown"); logWindowFrame("onWindowShown"); dumpImeTree(); });
		} else {
			logGeometry("onWindowShown(no decor)");
		}
	}


	@Override
	public void onConfigureWindow(android.view.Window win, boolean isFullscreen, boolean isCandidatesOnly) {
		super.onConfigureWindow(win, isFullscreen, isCandidatesOnly);
		// r57: THE stable margin fix. This ROM reserves a phantom ~30px "system bar" strip at the bottom of the IME
		// window whenever FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is set — and it IS set (drawsSysBars=true), because the
		// theme's windowDrawsSystemBarBackgrounds=false is ignored for IME windows here. onConfigureWindow runs
		// BEFORE the window is laid out on every show, so clearing the flag here prevents the strip from ever being
		// reserved: no margin, no 54px<->24px oscillation (which is what r56's post-layout removal caused). If the
		// FLAGS log still shows drawsSysBars=true after this, the flag is being re-set even later and we escalate.
		if (win != null) {
			win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
			win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
			win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
			// r59: THE correct lever. The 30px is PhoneWindow fitting the IME window's bottom system-window inset as
			// a MARGIN on its decor content (the stock action_mode_bar_stub/content/parentPanel tree). r58 called
			// decor.setFitsSystemWindows(false) — wrong object; PhoneWindow's content-insetting is controlled by the
			// WINDOW-level Window.setDecorFitsSystemWindows(boolean) (API 30, which this device is). Turning it off
			// tells PhoneWindow NOT to inset its content for system windows, so the 30px margin is never applied.
			// navBar=0 here, so drawing edge-to-edge at the bottom costs nothing. This is why KikaIME needs no such
			// call: its 2018 support-lib/compileSdk build predates this decor-fits-system-windows insetting entirely.
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
				win.setDecorFitsSystemWindows(false);
			}
			// r60: exhaust the window-level levers that stop the framework reserving a bottom strip, all at once.
			// LAYOUT_NO_LIMITS lets the docked window reach the true screen edge instead of stopping at an inset;
			// LAYOUT_IN_SCREEN lets it lay out over the whole screen. navBar=0, so neither can spill under a real
			// nav bar (the earlier worry that made me drop NO_LIMITS). (INSET_DECOR is deliberately NOT set — it
			// would re-inset the content for decor, the opposite of what we want.)
			win.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
			win.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
			final View decor = win.getDecorView();
			if (decor != null) {
				decor.setFitsSystemWindows(false);
				// Lay the decor out as if the system bars are hidden, so no space is reserved for them.
				decor.setSystemUiVisibility(
					android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
						| android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
						| android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
				decor.setOnApplyWindowInsetsListener((v, insets) -> insets.consumeSystemWindowInsets());
				decor.requestApplyInsets();
			}
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
	public static final String KT9_BUILD = "KT9 build r61 — WORK AROUND the ROM margin (it is unremovable). r57–r60 proved this TCL ROM bakes a fixed 30px bottom margin into the IME decor that no window API touches (mB stayed 30 through every flag; sysBottom=0 shows it is not even inset-derived). So instead of removing it: onComputeInsets now reserves only the bar's height (contentTopInsets = the margin, so the app avoids just the bottom ~24px and stops over-pushing by 30px), and applyFlushBarShift() shifts the bar DOWN by the margin (View.translationY) with clipChildren disabled on the decor subtree, so the bar renders flush at the true screen bottom. Net: app content ends exactly at the bar's top, bar sits flush at the bottom, no gap — the KikaIME look, achieved by moving the bar rather than fighting the ROM. If the bar renders clipped/half, the clip-disable missed an ancestor and I widen it. Prior: r55 push-up; r57 no oscillation.";

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
