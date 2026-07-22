package io.github.sspanak.tt9.commands;

import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;

public class CmdVoiceInput implements Command {
	public static final String ID = "key_voice_input";
	@Override public String getId() { return ID; }
	@Override public int getIcon() { return R.drawable.ic_fn_voice; }
	public int getIconOff() { return R.drawable.ic_fn_voice_off; }
	@Override public int getName() { return R.string.function_voice_input; }
	@Override public int getHardKey() { return 3; }
	@Override public int getPaletteKey() { return R.id.soft_key_3; }

	public boolean isActive(@Nullable TraditionalT9 tt9) {
		return tt9 != null && tt9.isVoiceInputActive();
	}

	@Override
	public boolean isAvailable(@Nullable TraditionalT9 tt9) {
		return
			tt9 != null
			&& !tt9.shouldBeOff()
			&& !isMissing(tt9);
	}

	public boolean isMissing(@Nullable TraditionalT9 tt9) {
		return tt9 != null && tt9.isVoiceInputMissing();
	}

	@Override
	public boolean run(TraditionalT9 tt9) {
		if (tt9 == null) {
			return false;
		}

		switchOutOf123(tt9);
		tt9.toggleVoiceInput();
		return true;
	}


	/**
	 * KT9 fork: voice input appeared to do nothing at all in 123 mode. It actually starts and
	 * records normally, but the transcription is handed to onText(), and Mode123.shouldIgnoreText()
	 * drops any string longer than a single character - so a spoken phrase vanished silently, with
	 * no error and no hint as to why.
	 *
	 * Anyone triggering voice input in 123 mode wants to dictate words, not digits, so switch to EN
	 * (ABC) letter mode first instead of failing silently. This is only done when the field actually
	 * permits letter input; in a genuinely numeric field (phone number, PIN) the mode is left alone,
	 * since letters would be rejected by the field regardless of the keyboard mode.
	 */
	private void switchOutOf123(TraditionalT9 tt9) {
		// when voice is already running, this press is a "stop", not a "start"
		if (tt9.isVoiceInputActive() || !InputModeKind.is123(tt9.getInputMode())) {
			return;
		}

		if (tt9.getAllowedInputModes().contains(InputMode.MODE_ABC)) {
			tt9.setInputMode(InputMode.MODE_ABC, InputMode.CASE_CAPITALIZE, false);
		}
	}
}
