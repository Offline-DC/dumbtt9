package io.github.sspanak.tt9.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.util.Logger;

/**
 * KT9 fork: a snappy, screen-centered input-mode indicator (en / En / EN / 123 / TT9).
 *
 * It uses a PopupWindow rather than a Toast because:
 *   - it appears instantly and we control the on-screen position (Toasts are delayed and stuck at the
 *     bottom, and text-Toast gravity is ignored on Android 11+);
 *   - custom-view Toasts are silently dropped on Android 11+, so they never appear at all.
 *
 * The popup is shown as an INDEPENDENT input-method dialog window (setWindowLayoutType), not a child of
 * the keyboard window. That is what lets the same pill appear both while typing (keyboard window up) and
 * at rest (keyboard window hidden to remove the bar) — a child popup would vanish with the hidden window.
 */
public class ModePopup {
	private static final long VISIBLE_MS = 900;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable hideRunnable = this::hide;

	@Nullable private PopupWindow popup;
	@Nullable private TextView label;

	public void show(@NonNull InputMethodService ims, @NonNull String text) {
		show(ims, text, true);
	}

	private void show(@NonNull InputMethodService ims, @NonNull String text, boolean allowRetry) {
		final View anchor = getAnchor(ims);
		if (anchor == null || anchor.getWindowToken() == null) {
			// The IME window/token isn't ready yet (can briefly happen right as the keyboard opens). Try
			// once more a beat later; if still unavailable, skip silently (no toast — it would look
			// different from the pill).
			if (allowRetry) {
				handler.postDelayed(() -> show(ims, text, false), 60);
			}
			return;
		}

		try {
			if (popup == null || label == null) {
				label = buildPill(ims);
				popup = new PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
				popup.setTouchable(false);
				popup.setFocusable(false);
				popup.setClippingEnabled(false);
				// THE KEY FIX: make the pill an INDEPENDENT input-method dialog window instead of the default
				// child panel of the keyboard window. We hide the keyboard window at rest to remove the bar;
				// a child popup vanishes with it (isShowing() stays true but nothing draws — that's the bug
				// you saw). A TYPE_INPUT_METHOD_DIALOG window floats above the keyboard and survives the bar
				// being hidden, so the same pill shows everywhere — typing or at rest.
				popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG);
			}

			label.setText(text);

			if (popup.isShowing()) {
				popup.update();
			} else {
				// Coordinates are relative to the anchor's window (which sits at the bottom of the screen),
				// so -y moves the pill up toward the middle. A positive offset would push it off-screen.
				final int upOffset = -Math.round(ims.getResources().getDisplayMetrics().heightPixels / 3f);
				popup.showAtLocation(anchor, Gravity.CENTER, 0, upOffset);
			}

			Logger.d("KT9pop", "shown '" + text + "' isShowing=" + popup.isShowing());
			handler.removeCallbacks(hideRunnable);
			handler.postDelayed(hideRunnable, VISIBLE_MS);
		} catch (Exception e) {
			Logger.d("KT9pop", "show failed: " + e.getMessage());
		}
	}

	private void hide() {
		try {
			if (popup != null && popup.isShowing()) {
				popup.dismiss();
			}
		} catch (Exception ignored) {
			// window may already be gone
		}
	}

	@Nullable
	private View getAnchor(@NonNull InputMethodService ims) {
		final Window window = ims.getWindow() != null ? ims.getWindow().getWindow() : null;
		return window != null ? window.getDecorView() : null;
	}

	@NonNull
	private TextView buildPill(@NonNull Context context) {
		final float density = context.getResources().getDisplayMetrics().density;
		final int padV = Math.round(7 * density);
		final int fixedWidth = Math.round(88 * density); // fixed so the bubble never resizes per mode

		TextView pill = new TextView(context);
		pill.setWidth(fixedWidth);
		pill.setTextColor(0xFFFFFFFF);
		pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
		pill.setTypeface(Typeface.DEFAULT_BOLD);
		pill.setGravity(Gravity.CENTER);
		pill.setPadding(0, padV, 0, padV);

		GradientDrawable background = new GradientDrawable();
		background.setColor(0xE6202632); // dark, slightly translucent
		background.setCornerRadius(16 * density);
		pill.setBackground(background);

		return pill;
	}
}
