package io.github.sspanak.tt9.ime.helpers;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import io.github.sspanak.tt9.util.sys.HardwareInfo;

import java.util.HashMap;
import java.util.HashSet;

/**
 * KT9 fork: the slide-out keyboard's Fn and Shift layers.
 *
 * <h3>Why the keyboard has to do this</h3>
 *
 * The HIT slider prints a second, gold symbol on most keys, and has its own Fn and Shift keys.
 * Nothing on the device implements any of it. Verified with {@code getevent}: hold Fn, press a
 * key, and the plain base scancode comes through unchanged -- the kernel does not translate. And
 * {@code mtk-kpd.kl} maps the two modifiers to
 *
 * <pre>key 258   BUTTON_3     (Fn)        key 259   BUTTON_4     (Shift)</pre>
 *
 * which Android treats as GAMEPAD FACE BUTTONS. They set no meta state, so {@code
 * KeyCharacterMap} never consults an alt layer and never capitalises anything. Without this
 * class, every gold symbol is unreachable and there is no way to type a capital letter on the
 * slide-out keyboard at all.
 *
 * <h3>Where the table came from</h3>
 *
 * Recovered from the TT9 build baked into the ROM at {@code /system/app/HitIME/HitIME.apk} --
 * which is upstream TT9 patched by the ROM builder to do exactly this job. Its obfuscated
 * {@code LR0/f;->h(I)Z} ("handleHitSlideInput") maps each keycode to Hebrew / lowercase / Fn /
 * uppercase and commits it through the InputConnection. This table is that method's Fn column,
 * transcribed verbatim, so Fn behaves identically to the factory keyboard.
 *
 * Its own log strings name the keys: {@code "HIT Function Key (190)"} and
 * {@code "HIT Shift Key (191)"}.
 *
 * Do not "correct" the odd-looking entries against a US layout -- they are what is printed on
 * this hardware. Fn+1 is a tilde, not an exclamation mark. S and D give inverted Spanish
 * punctuation. Z gives an emoji. A, C, Q, V and X have no Fn symbol at all, and are deliberately
 * absent so they fall through and type their normal letter.
 *
 * <h3>Where it sits</h3>
 *
 * Runs BEFORE the passthrough check in {@link io.github.sspanak.tt9.ime.KeyPadHandler}, so it
 * stays alive while the slide is out and TT9 is otherwise standing aside. That is the point:
 * these two layers are the one thing the platform genuinely cannot do for us.
 *
 * The modifiers are sticky, matching the factory keyboard: press once for the next key only,
 * twice to lock it on, a third time to clear it.
 */
public class FnKeyLayer {
	/** "HIT Function Key" per the ROM's own logging. mtk-kpd scancode 258. */
	private static final int KEYCODE_FN = KeyEvent.KEYCODE_BUTTON_3;   // 190
	/** "HIT Shift Key" per the ROM's own logging. mtk-kpd scancode 259. */
	private static final int KEYCODE_SHIFT = KeyEvent.KEYCODE_BUTTON_4; // 191

	private boolean fnOnce = false;
	private boolean fnLocked = false;
	private boolean shiftOnce = false;
	private boolean shiftLocked = false;

	/** Keys we translated, so their ACTION_UP is swallowed too. */
	private final HashSet<Integer> consumed = new HashSet<>();

	/**
	 * Fn + key -> the character printed in gold on that keycap. Transcribed from the ROM's own
	 * implementation (see the class doc); the number row cross-checks against a photo of the
	 * hardware.
	 */
	private static final HashMap<Integer, String> FN_SYMBOLS = new HashMap<>();
	static {
		// number row
		FN_SYMBOLS.put(KeyEvent.KEYCODE_1, "~");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_2, "@");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_3, "#");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_4, "$");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_5, "%");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_6, "^");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_7, "&");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_8, "*");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_9, "(");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_0, ")");
		// top letter row
		FN_SYMBOLS.put(KeyEvent.KEYCODE_W, "-");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_E, "_");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_R, "+");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_T, "=");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_Y, "[");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_U, "]");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_I, "{");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_O, "}");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_P, "\\");
		// home row
		FN_SYMBOLS.put(KeyEvent.KEYCODE_S, "¡"); // inverted !
		FN_SYMBOLS.put(KeyEvent.KEYCODE_D, "¿"); // inverted ?
		FN_SYMBOLS.put(KeyEvent.KEYCODE_F, ";");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_G, ":");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_H, "'");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_J, "\"");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_K, "<");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_L, ">");
		// bottom row
		FN_SYMBOLS.put(KeyEvent.KEYCODE_Z, "😀"); // yes, really: the factory build types an emoji here
		FN_SYMBOLS.put(KeyEvent.KEYCODE_B, "/");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_N, "!");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_M, "?");
		FN_SYMBOLS.put(KeyEvent.KEYCODE_PERIOD, ",");
		// A, C, Q, V and X carry no gold symbol on this hardware -- intentionally not mapped.
	}

	/**
	 * Gated on the HIT family. BUTTON_3/BUTTON_4 are real gamepad buttons everywhere else, and
	 * swallowing them on some other handset would be a bug, not a feature.
	 */
	public boolean isModifier(int keyCode) {
		return HardwareInfo.IS_HIT && (keyCode == KEYCODE_FN || keyCode == KEYCODE_SHIFT);
	}

	/**
	 * A modifier went down. Sticky, like the factory keyboard: once = next key only, twice =
	 * locked, three times = off. Pressing one modifier clears the other, so Fn and Shift can't
	 * fight.
	 */
	public void onModifierDown(int keyCode) {
		if (keyCode == KEYCODE_FN) {
			shiftOnce = shiftLocked = false;
			if (!fnOnce && !fnLocked) {
				fnOnce = true;
			} else if (fnOnce && !fnLocked) {
				fnLocked = true;
			} else {
				fnOnce = fnLocked = false;
			}
		} else if (keyCode == KEYCODE_SHIFT) {
			fnOnce = fnLocked = false;
			if (!shiftOnce && !shiftLocked) {
				shiftOnce = true;
			} else if (shiftOnce && !shiftLocked) {
				shiftLocked = true;
			} else {
				shiftOnce = shiftLocked = false;
			}
		}
	}

	/**
	 * What this key should type right now, or null to leave it alone so the platform types it
	 * normally. Null whenever no modifier is active, so ordinary typing is completely untouched.
	 */
	@Nullable
	public String textFor(int keyCode, KeyEvent event) {
		if (!HardwareInfo.IS_HIT) {
			return null;
		}

		String out = null;

		if (fnOnce || fnLocked) {
			out = FN_SYMBOLS.get(keyCode);
			// A key with no gold symbol types its normal character -- it does not eat the press.
			if (out != null && !fnLocked) {
				fnOnce = false;
			}
		} else if (shiftOnce || shiftLocked) {
			int unicode = event.getUnicodeChar(0); // 0: ignore any meta state the platform has
			if (unicode != 0) {
				out = String.valueOf(Character.toUpperCase((char) unicode));
				if (!shiftLocked) {
					shiftOnce = false;
				}
			}
		}

		if (out != null) {
			consumed.add(keyCode);
		}
		return out;
	}

	/**
	 * True when this key's ACTION_DOWN was translated, so its ACTION_UP must be swallowed as well
	 * -- otherwise the app sees a stray key release. Tracked per key rather than off the modifier
	 * state, because a one-shot modifier is already cleared by the time the UP arrives.
	 */
	public boolean consumesUp(int keyCode) {
		return consumed.remove(keyCode);
	}

	/** Drop all modifier state, e.g. when the field changes. */
	public void reset() {
		fnOnce = fnLocked = shiftOnce = shiftLocked = false;
		consumed.clear();
	}
}
