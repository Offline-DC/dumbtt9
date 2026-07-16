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
 * The popup is anchored to the IME window's decor view purely for its window token — the token stays
 * valid even while the keyboard itself is hidden (as in ABC/123), so the indicator still shows when
 * cycling modes. If the popup can't be shown for any reason, it falls back to a plain toast.
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
		Logger.d("KT9pop", "show '" + text + "' anchor=" + (anchor != null) + " token=" + (anchor != null && anchor.getWindowToken() != null) + " retry=" + allowRetry);
		if (anchor == null || anchor.getWindowToken() == null) {
			// First mode change right after the keyboard opens can land before the IME window is attached
			// (no token yet). Rather than give up to a delayed toast, wait one short beat and try again;
			// only fall back if it is still not ready.
			if (allowRetry) {
				handler.postDelayed(() -> show(ims, text, false), 60);
			} else {
				fallback(ims, text);
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
				// The popup anchors to the keyboard window, which sits at the BOTTOM of the screen, so
				// its coordinate space is relative to that window: +y is down (off-screen), -y is up.
				// Center it and lift it up toward the middle of the screen. (A positive/TOP offset here
				// pushes it below the screen and it vanishes.)
				final int upOffset = -Math.round(ims.getResources().getDisplayMetrics().heightPixels / 3f);
				popup.showAtLocation(anchor, Gravity.CENTER, 0, upOffset);
			}

			Logger.d("KT9pop", "shown '" + text + "' isShowing=" + popup.isShowing());
			handler.removeCallbacks(hideRunnable);
			handler.postDelayed(hideRunnable, VISIBLE_MS);
		} catch (Exception e) {
			Logger.d("KT9pop", "show failed: " + e.getMessage() + " -> toast fallback");
			fallback(ims, text);
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

	private void fallback(@NonNull Context context, @NonNull String text) {
		UI.toastShortSingle(context, "kt9_mode_popup", text);
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
