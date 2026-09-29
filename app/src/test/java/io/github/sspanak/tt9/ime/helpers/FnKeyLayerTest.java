package io.github.sspanak.tt9.ime.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class FnKeyLayerTest {
	private static final int FN = KeyEvent.KEYCODE_BUTTON_3;
	private static final int SHIFT = KeyEvent.KEYCODE_BUTTON_4;

	@Test public void nonHit_fnAndShiftAreNotModifiers() {
		FnKeyLayer f = new FnKeyLayer(false);
		assertFalse(f.isModifier(FN));
		assertFalse(f.isModifier(SHIFT));
	}
	@Test public void nonHit_textForAlwaysNull() {
		FnKeyLayer f = new FnKeyLayer(false);
		f.onModifierDown(FN);
		assertNull(f.textFor(KeyEvent.KEYCODE_1, null));
	}
	@Test public void hit_fnAndShiftAreModifiers() {
		FnKeyLayer f = new FnKeyLayer(true);
		assertTrue(f.isModifier(FN));
		assertTrue(f.isModifier(SHIFT));
	}
	@Test public void hit_fnPlusOneTypesTilde() {
		FnKeyLayer f = new FnKeyLayer(true);
		f.onModifierDown(FN);
		assertEquals("~", f.textFor(KeyEvent.KEYCODE_1, null));
	}
	@Test public void hit_fnIsOneShotByDefault() {
		FnKeyLayer f = new FnKeyLayer(true);
		f.onModifierDown(FN);
		assertEquals("~", f.textFor(KeyEvent.KEYCODE_1, null));
		assertNull(f.textFor(KeyEvent.KEYCODE_2, null));
	}
	@Test public void hit_fnDoubleTapLocks() {
		FnKeyLayer f = new FnKeyLayer(true);
		f.onModifierDown(FN);
		f.onModifierDown(FN);
		assertEquals("~", f.textFor(KeyEvent.KEYCODE_1, null));
		assertEquals("@", f.textFor(KeyEvent.KEYCODE_2, null));
	}
	@Test public void hit_unmappedKeyFallsThrough() {
		FnKeyLayer f = new FnKeyLayer(true);
		f.onModifierDown(FN);
		assertNull(f.textFor(KeyEvent.KEYCODE_A, null));
	}
}
