package io.github.sspanak.tt9.ime;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import io.github.sspanak.tt9.ime.helpers.FnKeyLayer;
import io.github.sspanak.tt9.ime.helpers.Key;
import io.github.sspanak.tt9.preferences.screens.debug.DropDownInputHandlingMode;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Timer;
import io.github.sspanak.tt9.util.sys.SliderKeyboard;


abstract class KeyPadHandler extends UiHandler {
	// KT9 fork: whether the slide-out hardware QWERTY is out. While it is, we hand every key
	// straight to the app -- see SliderKeyboard and determineInputModeId().
	protected final SliderKeyboard sliderKeyboard = new SliderKeyboard();

	// KT9 fork: the slide-out keyboard's Fn symbol layer. Nothing on the handset implements it,
	// so we do -- including while the slide is out and we are otherwise in passthrough.
	protected final FnKeyLayer fnKeyLayer = new FnKeyLayer();

	// KT9 fork: true while we are swallowing a BACK press we turned into a backspace, so its
	// ACTION_UP is swallowed too. Without that, Activity.onKeyUp turns the lone release into
	// onBackPressed() and the screen closes under the user as they delete.
	private boolean backConsumedAsBackspace = false;

	// debounce handling
	private final static String DEBOUNCE_TIMER = "debounce_";

	// temporal key handling
	private int ignoreNextKeyUp = 0;

	private int lastKeyCode = 0;
	private int keyRepeatCounter = 0;

	private int lastNumKeyCode = 0;
	private int numKeyRepeatCounter = 0;


	/**
	 * Main initialization of the input method component. Be sure to call to
	 * super class.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		settings = new SettingsStore(getApplicationContext());

		onInit();
	}


	/**
	 * Use this to monitor key events being delivered to the application. We get
	 * first crack at them, and can either resume them or let them continue to
	 * the app.
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (debounceKey(keyCode, event)) {
			return true;
		}

		// KT9 fork: the slide-out keyboard's Fn and Shift layers, ahead of everything else --
		// including the passthrough check below, since the whole point is that they keep working
		// while the slide is out and TT9 is otherwise standing aside.
		if (fnKeyLayer.isModifier(keyCode)) {
			fnKeyLayer.onModifierDown(keyCode);
			return true; // swallow it: to the app it is a stray gamepad button
		}
		String fnText = fnKeyLayer.textFor(keyCode, event);
		if (fnText != null) {
			return commitFnSymbol(fnText);
		}

		// KT9 fork: with the slide out, BACK is the keyboard's backspace -- the slide-out keyboard has
		// no separate delete key, and mtk-kpd.kl maps its top-right key to KEYCODE_BACK. Matches what
		// the HIT's own factory keyboard does.
		//
		// Note the condition: only when there is actually something before the cursor to delete. On an
		// empty field the press falls through and does its normal job, so the user can still leave the
		// screen and is never trapped. Auto-repeat gives hold-to-delete for free, one character per
		// repeat. Lifted from the factory build's own implementation.
		if (sliderKeyboard.isOpen() && keyCode == KeyEvent.KEYCODE_BACK && deleteCharBeforeCursor()) {
			backConsumedAsBackspace = true;
			return true;
		}

		// KT9 fork: a letter can only have come from the slide-out keyboard. Re-resolve the mode
		// BEFORE shouldBeOff() below, so this very keypress passes through instead of being the one
		// key that gets eaten on the way into passthrough.
		if (sliderKeyboard.onKeyDown(keyCode)) {
			onSliderKeyboardChanged();
		}

		if (settings.getInputHandlingMode() == DropDownInputHandlingMode.RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == DropDownInputHandlingMode.CALL_SUPER) {
			return super.onKeyDown(keyCode, event);
		}

		if (shouldBeOff()) {
			// KT9 fork: on a cold resume the mode can still be PASSTHROUGH because the connection was
			// not ready when the field started. If it is live now, re-resolve the mode so this first key
			// is typed properly instead of leaking to the app as a raw digit (the "2awesome" bug).
			recoverModeIfConnectionWasNotReady();
			if (shouldBeOff()) {
				return false;
			}
		}

//		Logger.d("onKeyDown", "Key: " + event + " repeat?: " + event.getRepeatCount() + " long-time: " + event.isLongPress());

		// "backspace" key must repeat its function when held down, so we handle it in a special way
		if (Key.isBackspace(settings, keyCode)) {
			if (onBackspace(event.getRepeatCount())) {
				return Key.setHandled(KeyEvent.KEYCODE_DEL, true);
			} else {
				Key.setHandled(KeyEvent.KEYCODE_DEL, false);
			}
		}

		// start tracking key hold
		if (Key.isNumber(keyCode)) {
			event.startTracking();
			return true;
		}
		else if (getFinalContext().isHoldHotkey(-keyCode)) {
			event.startTracking();
		}

		// on many devices there is a default back handler, so we must fall back to it when we don't
		// perform any operation
		if (Key.isBack(keyCode)) {
			Key.setHandled(keyCode, onBack());
			return Key.isHandledInSuper(keyCode) ? super.onKeyDown(keyCode, event) : Key.isHandled(keyCode);
		} else {
			Key.setHandled(KeyEvent.KEYCODE_BACK, false);
		}

		// KT9 fork: swallow auto-repeat on the OK key. Holding OK even slightly too long makes
		// Android emit repeat events (~400ms, then every ~50ms). Each one re-entered onOK(), and
		// after the first press had already consumed the suggestion/panel, the repeats fell through
		// to performing the field's editor action — firing IME_ACTION_NEXT and then ENTER dozens of
		// times. In a login form that jumped to the password field and kept hammering it, so the
		// user could not get back. Repeats are consumed here (return true) rather than ignored, so
		// they do not leak through to the app either. A genuine hold is handled by onKeyLongPress.
		if (Key.isOK(keyCode) && event.getRepeatCount() > 0) {
			return true;
		}

		return
			Key.setHandled(KeyEvent.KEYCODE_ENTER, Key.isOK(keyCode) && onOK())
			|| handleHotkey(keyCode, true, false, true) // hold a hotkey, handled in onKeyLongPress())
			|| handleHotkey(keyCode, false, keyRepeatCounter + 1 > 0, true) // press a hotkey, handled in onKeyUp()
			|| Key.isPoundOrStar(keyCode) && onText(charForPoundOrStar(keyCode, event), true)
			|| super.onKeyDown(keyCode, event); // let the system handle the keys we don't care about (usually, the touch "buttons")
	}


	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (settings.getInputHandlingMode() == DropDownInputHandlingMode.RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == DropDownInputHandlingMode.CALL_SUPER) {
			return super.onKeyLongPress(keyCode, event);
		}

		if (shouldBeOff()) {
			return false;
		}

//		Logger.d("onLongPress", "LONG PRESS: " + keyCode);

		if (event.getRepeatCount() > 1) {
			return true;
		}

		ignoreNextKeyUp = keyCode;
		if (Key.isNumber(keyCode)) {
			numKeyRepeatCounter = 0;
			lastNumKeyCode = 0;
			return onNumber(Key.codeToNumber(settings, keyCode), true, 0);
		} else {
			keyRepeatCounter = 0;
			lastKeyCode = 0;
		}

		if (handleHotkey(keyCode, true, false, false)) {
			return true;
		}

		ignoreNextKeyUp = 0;
		return false;
	}


	/**
	 * Use this to monitor key events being delivered to the application. We get
	 * first crack at them, and can either resume them or let them continue to
	 * the app.
	 */
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (debounceKey(keyCode, event)) {
			return true;
		}

		// KT9 fork: mirror of the Fn/Shift handling in onKeyDown. The modifiers are sticky, so the
		// release does nothing but get swallowed -- state is toggled on the way DOWN.
		if (fnKeyLayer.isModifier(keyCode)) {
			return true;
		}
		if (fnKeyLayer.consumesUp(keyCode)) {
			return true;
		}

		// KT9 fork: swallow the release of a BACK we turned into a backspace (see onKeyDown).
		if (keyCode == KeyEvent.KEYCODE_BACK && backConsumedAsBackspace) {
			backConsumedAsBackspace = false;
			return true;
		}

		if (settings.getInputHandlingMode() == DropDownInputHandlingMode.RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == DropDownInputHandlingMode.CALL_SUPER) {
			return super.onKeyUp(keyCode, event);
		}

		if (shouldBeOff()) {
			return false;
		}

//		Logger.d("onKeyUp", "Key: " + keyCode + " repeat?: " + event.getRepeatCount());

		if (keyCode == ignoreNextKeyUp) {
//			Logger.d("onKeyUp", "Ignored: " + keyCode);
			ignoreNextKeyUp = 0;
			return true;
		}

		if (Key.isBackspace(settings, keyCode) && Key.isHandled(KeyEvent.KEYCODE_DEL)) {
			return true;
		}

		keyRepeatCounter = (lastKeyCode == keyCode) ? keyRepeatCounter + 1 : 0;
		lastKeyCode = keyCode;

		if (Key.isNumber(keyCode)) {
			numKeyRepeatCounter = (lastNumKeyCode == keyCode) ? numKeyRepeatCounter + 1 : 0;
			lastNumKeyCode = keyCode;
			return onNumber(Key.codeToNumber(settings, keyCode), false, numKeyRepeatCounter);
		}

		if (Key.isBack(keyCode)) {
			return Key.isHandledInSuper(keyCode) ? super.onKeyUp(keyCode, event) : Key.isHandled(keyCode);
		}

		return
			(Key.isOK(keyCode) && Key.isHandled(KeyEvent.KEYCODE_ENTER))
			|| handleHotkey(keyCode, false, keyRepeatCounter > 0, false)
			|| Key.isPoundOrStar(keyCode) && onText(charForPoundOrStar(keyCode, event), false)
			|| super.onKeyUp(keyCode, event); // let the system handle the keys we don't care about (usually, the touch "buttons")
	}


	private boolean handleHotkey(int keyCode, boolean hold, boolean repeat, boolean validateOnly) {
		return onHotkey(keyCode * (hold ? -1 : 1), repeat, validateOnly);
	}


	/**
	 * KT9 fork: some phone keypads (e.g. the TCL flip) report a blank/space unicode char for the "*"
	 * key, so the text passed to onText() was a space instead of "*", breaking the punctuation
	 * handler. Map by keycode so "*" and "#" are always correct regardless of the device keymap.
	 */
	private String charForPoundOrStar(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_STAR) {
			return "*";
		}
		if (keyCode == KeyEvent.KEYCODE_POUND) {
			return "#";
		}
		return String.valueOf((char) event.getUnicodeChar());
	}


	/**
	 * KT9 fork: type one Fn-layer symbol straight into the field.
	 *
	 * Goes through the InputConnection rather than TT9's own onText(), because this has to work
	 * while the slide is out -- and there we are in MODE_PASSTHROUGH, where the typing pipeline is
	 * deliberately shut down. Returns true either way: if there is no connection the key is still
	 * swallowed rather than leaking to the app as a bare digit, which is what the user would see
	 * as "Fn did nothing and then typed 1 anyway".
	 */
	/**
	 * KT9 fork: delete one character before the cursor, for the slide-out keyboard's BACK-as-
	 * backspace. Returns false when there is nothing to delete, which is what lets the press fall
	 * through to ordinary back navigation instead of trapping the user on the screen.
	 *
	 * Straight through the InputConnection rather than TT9's own onBackspace(), because this has to
	 * work while the slide is out -- and there we are in MODE_PASSTHROUGH, where the typing
	 * pipeline, the composing text and the suggestion engine are all deliberately shut down.
	 */
	private boolean deleteCharBeforeCursor() {
		android.view.inputmethod.InputConnection connection = getCurrentInputConnection();
		if (connection == null) {
			return false;
		}
		CharSequence before = connection.getTextBeforeCursor(1, 0);
		if (before == null || before.length() == 0) {
			return false;
		}
		connection.deleteSurroundingText(1, 0);
		return true;
	}


	private boolean commitFnSymbol(String symbol) {
		android.view.inputmethod.InputConnection connection = getCurrentInputConnection();
		if (connection != null) {
			connection.commitText(symbol, 1);
		}
		return true;
	}


	/**
	 * KT9 fork: the slide just opened or closed. Restart the current field so the input mode is
	 * re-resolved (to MODE_PASSTHROUGH while it is open) and the keyboard window follows --
	 * TraditionalT9.onStart() already calls onStop() for a passthrough mode, which hides it.
	 */
	protected void onSliderKeyboardChanged() {
		EditorInfo field = getCurrentInputEditorInfo();
		if (field != null) {
			onStart(field, true);
		}
	}


	public void resetKeyRepeat() {
		numKeyRepeatCounter = 0;
		keyRepeatCounter = 0;
		lastNumKeyCode = 0;
		lastKeyCode = 0;
	}


	private boolean debounceKey(int keyCode, KeyEvent event) {
		if (settings.getKeyPadDebounceTime() <= 0 || event.isLongPress()) {
			return false;
		}

		String keyTimer = DEBOUNCE_TIMER + keyCode;

		if (Timer.get(keyTimer) > 0 && Timer.get(keyTimer) < settings.getKeyPadDebounceTime()) {
			return true;
		}

		if (event.getAction() == KeyEvent.ACTION_UP) {
			Timer.start(keyTimer);
		}

		return false;
	}
}
