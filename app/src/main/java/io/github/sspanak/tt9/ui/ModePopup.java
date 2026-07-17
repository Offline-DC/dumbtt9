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
 * The popup is anchored to the IME window's decor view and floats above the keyboard bar. Since the bar
 * (KikaIME-style) is always shown while a text field is focused, the pill's host window is always present,
 * so the same pill appears everywhere — while composing and at rest.
 */
public class ModePopup {
	private static final long VISIBLE_MS = 900;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable hideRunnable = this::hide;

	@Nullable private PopupWindow popup;
	@Nullable private TextView label;

	public void show(@NonNull InputMethodService ims, @NonNull String text) {
		show(ims, text, 4);
	}

	private void show(@NonNull InputMethodService ims, @NonNull String text, int triesLeft) {
		final View anchor = getAnchor(ims);
		if (anchor == null || anchor.getWindowToken() == null) {
			// The IME window/token isn't ready yet (can briefly happen right as the keyboard opens on this slow
			// device). Retry a few times a beat apart; if still unavailable, skip silently (no toast — it would
			// look different from the pill).
			if (triesLeft > 0) {
				handler.postDelayed(() -> show(ims, text, triesLeft - 1), 60);
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
			}

			label.setText(text);

			if (popup.isShowing()) {
				popup.update();
			} else {
				// r49: the IME window is now only as tall as the bar (KikaIME structure — no full-screen host),
				// so Gravity.CENTER would center on the bar and push the pill off the bottom of the screen —
				// that is why the pill stopped appearing. The IME window's BOTTOM always aligns with the screen
				// bottom (window gravity = bottom), so anchor to Gravity.BOTTOM and offset UP by ~55% of the
				// screen height to float the pill in the upper-middle, regardless of how tall the bar currently
				// is. setClippingEnabled(false) (set above) lets the pill extend beyond the short window.
				final int upFromBottom = Math.round(ims.getResources().getDisplayMetrics().heightPixels * 0.55f);
				popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, upFromBottom);
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
