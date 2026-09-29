package io.github.sspanak.tt9.util.sys;

import android.content.res.Configuration;
import android.view.KeyEvent;

/**
 * KT9 fork: tracks whether a phone's slide-out hardware QWERTY is currently exposed.
 *
 * When it is, TT9 has nothing useful to offer and plenty to get wrong: the user has real
 * letter keys, a real number row, a real Enter and a real Backspace, and the platform's own
 * key character map already turns all of them into text. T9 on top of that is not a feature,
 * it is interference -- the number row gets eaten as T9 keys ("1" opens the punctuation panel
 * instead of typing 1), Enter is swallowed as the OK key, Backspace runs a composing-aware
 * delete over text TT9 never saw typed, and the suggestion engine builds a word out of keys it
 * is only seeing half of. So the keyboard steps aside entirely (MODE_PASSTHROUGH) while the
 * slide is out, and comes back the moment it is closed.
 *
 * <h3>Why this class has to guess</h3>
 *
 * Android cannot see the slider on the hardware this was written for (the HIT / MT6739,
 * Android 8.1). Both of that phone's input devices -- the front keypad and the slide-out
 * QWERTY -- are the SAME kernel device ("mtk-kpd"), and both are declared alphabetic and
 * always present. So the platform reports {@code qwerty/v/v} (hardware keyboard present and
 * NOT hidden) in both positions, identically, and {@code hardKeyboardHidden} never changes.
 * The HALL sensor emits nothing to userspace either. Verified on-device with
 * {@code dumpsys window}, {@code dumpsys input} and {@code getevent}.
 *
 * What DOES change is the screen: 240x291 portrait when shut, 320x204 landscape when open.
 * So orientation is the signal, and {@link Configuration#keyboard} is only the gate.
 *
 * <h3>Staying inert on every other handset</h3>
 *
 * Gated on {@link HardwareInfo#IS_HIT} AND the device reporting {@link
 * Configuration#KEYBOARD_QWERTY}. The TCL Flip 2 fails both, so {@link #isOpen()} is permanently
 * false there and not one code path changes. That is deliberate: this must be a no-op on the
 * handset that is actually in customers' hands.
 */
public class SliderKeyboard {
	private boolean open = false;
	private boolean supported = false;
	private int lastOrientation = Configuration.ORIENTATION_UNDEFINED;

	/** True on HIT hardware. Injectable so tests exercise both paths without faking Build. */
	private final boolean hitHardware;

	public SliderKeyboard() { this(HardwareInfo.IS_HIT); }
	public SliderKeyboard(boolean hitHardware) { this.hitHardware = hitHardware; }

	/** True while the slide-out QWERTY is exposed and TT9 should keep its hands off. */
	public boolean isOpen() {
		return open;
	}

	/**
	 * Re-read the slide state from the current configuration. Call on every configuration change
	 * AND on every field start (the latter seeds the state, since a configuration change only
	 * fires on a change and there may not have been one since the service started).
	 *
	 * @return true when the state actually flipped, i.e. the caller must re-resolve the input mode.
	 */
	public boolean refresh(Configuration config) {
		if (config == null) {
			return false;
		}
		return refresh(config.keyboard, config.orientation);
	}

	/**
	 * Testable core -- primitives only, so unit tests need no Configuration instance.
	 * Package-visible on purpose (tests live in the same package).
	 */
	boolean refresh(int keyboardType, int orientation) {
		// Gated on the HIT family, not just "reports a QWERTY": a Bluetooth keyboard paired to any
		// other handset would otherwise put the whole app into passthrough.
		supported = hitHardware && keyboardType == Configuration.KEYBOARD_QWERTY;
		if (!supported) {
			lastOrientation = Configuration.ORIENTATION_UNDEFINED;
			return setOpen(false);
		}

		// ORIENTATION_UNDEFINED means "don't know", not "closed".
		if (orientation == Configuration.ORIENTATION_UNDEFINED) {
			return false;
		}

		// Only an actual CHANGE of orientation is a slide (onStart() calls this on every field start).
		if (orientation == lastOrientation) {
			return false;
		}
		lastOrientation = orientation;

		return setOpen(orientation == Configuration.ORIENTATION_LANDSCAPE);
	}

	/**
	 * Fallback signal: a key that only the slide-out keyboard has just arrived, so it must be out,
	 * whatever the orientation says. Covers firmware that exposes a QWERTY without rotating the
	 * screen for it.
	 *
	 * Deliberately one-way -- it can only conclude "open", never "closed". Being wrong in that
	 * direction costs the user T9 until the next configuration change; being wrong in the other
	 * direction would eat their keystrokes.
	 *
	 * @return true when the state actually flipped.
	 */
	public boolean onKeyDown(int keyCode) {
		if (!supported || open) {
			return false;
		}
		return isQwertyOnlyKey(keyCode) && setOpen(true);
	}

	private boolean setOpen(boolean value) {
		if (open == value) {
			return false;
		}
		open = value;
		return true;
	}

	/**
	 * Keys no phone keypad has. Letters are the obvious ones; the punctuation and bracket keys are
	 * included because a user may well open the slide and reach straight for one of them. Digits,
	 * "*", "#", the d-pad, OK, Back and Backspace are all deliberately absent -- they exist on the
	 * front keypad too, so they prove nothing.
	 */
	private static boolean isQwertyOnlyKey(int keyCode) {
		return (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
			|| keyCode == KeyEvent.KEYCODE_SEMICOLON
			|| keyCode == KeyEvent.KEYCODE_APOSTROPHE
			|| keyCode == KeyEvent.KEYCODE_LEFT_BRACKET
			|| keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET
			|| keyCode == KeyEvent.KEYCODE_BACKSLASH
			|| keyCode == KeyEvent.KEYCODE_SLASH
			|| keyCode == KeyEvent.KEYCODE_EQUALS
			|| keyCode == KeyEvent.KEYCODE_MINUS
			|| keyCode == KeyEvent.KEYCODE_AT
			|| keyCode == KeyEvent.KEYCODE_TAB;
	}
}
