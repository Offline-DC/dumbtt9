package io.github.sspanak.tt9.util.sys;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.view.KeyEvent;

import org.junit.Test;

public class SliderKeyboardTest {
	private static final int QWERTY = Configuration.KEYBOARD_QWERTY;
	private static final int NOKEYS = Configuration.KEYBOARD_NOKEYS;
	private static final int PORTRAIT = Configuration.ORIENTATION_PORTRAIT;
	private static final int LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE;

	@Test public void nonHit_neverOpens_evenWithQwertyLandscape() {
		SliderKeyboard s = new SliderKeyboard(false);
		s.refresh(QWERTY, LANDSCAPE);
		assertFalse(s.isOpen());
	}
	@Test public void nonHit_letterKeyDoesNotLatchOpen() {
		SliderKeyboard s = new SliderKeyboard(false);
		s.refresh(QWERTY, PORTRAIT);
		assertFalse(s.onKeyDown(KeyEvent.KEYCODE_A));
		assertFalse(s.isOpen());
	}
	@Test public void hit_opensInLandscape_closesInPortrait() {
		SliderKeyboard s = new SliderKeyboard(true);
		s.refresh(QWERTY, PORTRAIT); assertFalse(s.isOpen());
		s.refresh(QWERTY, LANDSCAPE); assertTrue(s.isOpen());
		s.refresh(QWERTY, PORTRAIT); assertFalse(s.isOpen());
	}
	@Test public void hit_letterKeyLatchesOpen() {
		SliderKeyboard s = new SliderKeyboard(true);
		s.refresh(QWERTY, PORTRAIT);
		assertTrue(s.onKeyDown(KeyEvent.KEYCODE_A));
		assertTrue(s.isOpen());
	}
	@Test public void hit_digitKeyDoesNotLatch() {
		SliderKeyboard s = new SliderKeyboard(true);
		s.refresh(QWERTY, PORTRAIT);
		assertFalse(s.onKeyDown(KeyEvent.KEYCODE_1));
		assertFalse(s.isOpen());
	}
	@Test public void hit_nonQwertyConfigNeverOpens() {
		SliderKeyboard s = new SliderKeyboard(true);
		s.refresh(NOKEYS, LANDSCAPE);
		assertFalse(s.isOpen());
	}
}
