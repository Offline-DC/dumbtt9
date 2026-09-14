package io.github.sspanak.tt9.util.sys;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

public class HardwareInfo {
	public static final boolean IS_CAT_S22_FLIP = Build.MANUFACTURER.equals("Cat") && Build.MODEL.contains("S22");
	public static final boolean IS_EMULATOR = (Build.MANUFACTURER.equalsIgnoreCase("google") || Build.MANUFACTURER.equalsIgnoreCase("unknown")) && Build.MODEL.toUpperCase().contains("SDK") && Build.MODEL.toUpperCase().contains("X86");
	public static final boolean IS_LG_X100S = Build.MANUFACTURER.equals("LGE") && Build.MODEL.contains("X100S");
	public static final boolean IS_QIN_F21 = Build.MANUFACTURER.equals("DuoQin") && Build.MODEL.contains("F21");
	public static final boolean IS_SAMSUNG = Build.MANUFACTURER.equals("samsung") || Build.MANUFACTURER.equals("Samsung") || Build.MANUFACTURER.equals("SAMSUNG");
	public static final boolean IS_SONIM = Build.MANUFACTURER.equals("Sonimtech");
	public static final boolean IS_XIAOMI = Build.MANUFACTURER.equals("Xiaomi");
	public static final boolean IS_TCL_FLIP = Build.MANUFACTURER.equalsIgnoreCase("TCL") && (Build.DEVICE.toLowerCase().contains("flip") || Build.MODEL.toUpperCase().startsWith("4058"));

	/**
	 * KT9 fork: the HIT QWERTY slider family (MT6739, Android 8.1).
	 *
	 * Reported identically across all three properties on the units we have -- brand, model and
	 * device are all literally "HIT". Matched loosely (prefix, any of the three) so a later model
	 * in the same line is picked up without another release; tighten it if a clash ever shows up.
	 *
	 * Everything HIT-specific in this app keys off this flag, so none of it can run on a TCL flip:
	 * the slide-out passthrough (SliderKeyboard), the Fn/Shift symbol layers (FnKeyLayer), and the
	 * no-touchscreen lock below.
	 */
	public static final boolean IS_HIT =
		Build.MODEL.toUpperCase().startsWith("HIT")
		|| Build.DEVICE.toUpperCase().startsWith("HIT")
		|| Build.MANUFACTURER.toUpperCase().startsWith("HIT");

	private static Boolean NO_TOUCH_SCREEN = null;

	private static Resources resources;


	protected static Resources getResources(Context context) {
		if (resources == null) {
			resources = context.getResources();
		}
		return resources;
	}


	public static float getScreenPixelDensity(Context context) {
		return getResources(context).getDisplayMetrics().density;
	}


	public static int getScreenWidth(Context context) {
		return getResources(context).getDisplayMetrics().widthPixels;
	}


	public static int getScreenHeight(Context context) {
		return getResources(context).getDisplayMetrics().heightPixels;
	}


	public static float getScreenHeightDp(Context context) {
		return getScreenHeight(context) / getScreenPixelDensity(context);
	}


	public static float getScreenWidthDp(Context context) {
		return getScreenWidth(context) / getScreenPixelDensity(context);
	}


	public static boolean noBackspaceKey() {
		return !KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DEL) && !KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_CLEAR);
	}


	public static boolean noKeyboard(Context context) {
		// all Xiaomi phones are only touchscreen, but some of them report they have a keyboard
		// See: https://github.com/sspanak/tt9/issues/549
		if (IS_XIAOMI) {
			return true;
		}

		Configuration configuration = getResources(context).getConfiguration();

		return
			(configuration.keyboard == Configuration.KEYBOARD_NOKEYS || configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES)
			&& !KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_STAR)
			&& !KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_POUND);
	}


	public static boolean noTouchScreen(Context context) {
		if (NO_TOUCH_SCREEN == null) {
			// Offline-DC ships on keypad hardware that LIES about this. Several TCL flips falsely
			// declare android.hardware.touchscreen, and so does the HIT -- `pm list features` there
			// lists touchscreen, multitouch and even "jazzhand", while the platform's own
			// Configuration reports "-touch" (TOUCHSCREEN_NOTOUCH). The Configuration is the honest
			// one, so trust it first and keep the per-device overrides as a backstop for anything
			// that lies in both places.
			int touchscreen = getResources(context).getConfiguration().touchscreen;
			NO_TOUCH_SCREEN =
				touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
				|| IS_TCL_FLIP
				|| IS_HIT
				|| !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
		}
		return NO_TOUCH_SCREEN;
	}


	public static boolean isSonimGen2(Context context) {
		return IS_SONIM && DeviceInfo.AT_LEAST_ANDROID_11 && noTouchScreen(context);
	}


	@NonNull
	@Override
	public String toString() {
		return "\"" + Build.MANUFACTURER + "\" " + "\"" + Build.MODEL + "\"";
	}
}
