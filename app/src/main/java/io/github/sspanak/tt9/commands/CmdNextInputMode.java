package io.github.sspanak.tt9.commands;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.ui.StatusIcon;

public class CmdNextInputMode implements Command {
	public static final String ID = "key_next_input_mode";
	public String getId() { return ID; }
	public int getIcon() { return StatusIcon.getCachedResourceId(); }
	public int getName() { return R.string.function_next_mode; }

	public void invalidateIcon(@Nullable TraditionalT9 tt9) {
		new StatusIcon(
			tt9 != null ? tt9.getInputMode() : null,
			tt9 != null ? tt9.getLanguage() : null,
			tt9 != null ? tt9.getDisplayTextCase() : 0
		);
	}

	@Override
	public boolean isAvailable(@Nullable TraditionalT9 tt9) {
		return
			tt9 != null
			&& tt9.getAllowedInputModes().size() > 1
			&& !tt9.shouldBeOff()
			&& !tt9.isVoiceInputActive()
			&& !InputModeKind.isPassthrough(tt9.getInputMode());
	}


	// KT9 fork: the "#" key steps through a fixed cycle:
	//   en (ABC lower, locked) -> En (ABC sentence case) -> EN (ABC upper, locked) -> 123 -> TT9 (predictive)
	// Each row is { modeId, forcedTextCase, lock(1/0) }. CASE_UNDEFINED means "don't force a case".
	private static final int[][] STEPS = {
		{ InputMode.MODE_ABC, InputMode.CASE_LOWER, 1 },            // en
		{ InputMode.MODE_ABC, InputMode.CASE_CAPITALIZE, 0 },       // En
		{ InputMode.MODE_ABC, InputMode.CASE_UPPER, 1 },            // EN
		{ InputMode.MODE_123, InputMode.CASE_UNDEFINED, 0 },        // 123
		{ InputMode.MODE_PREDICTIVE, InputMode.CASE_UNDEFINED, 0 }, // TT9
	};

	// Our position in the cycle above, tracked explicitly rather than re-derived from the
	// input mode on every press. In fields that force a fixed text case (email, password,
	// no-suggestions), setting the En/EN ABC cases doesn't stick — the field snaps back to
	// lowercase — so deriving the position from the mode's live case always resolves to the
	// "en" step, and the cycle can never advance to 123 or predictive. That left users stuck
	// on lowercase letters, unable to type numbers for a login or password. Static so it
	// survives the per-keypress command lookups; re-synced from the mode only when the mode
	// was changed outside this cycle (e.g. focusing a new field), so external changes still win.
	private static int cycleIndex = -1;


	public boolean run(@Nullable TraditionalT9 tt9) {
		if (tt9 == null) {
			return false;
		}

		tt9.getSuggestionOps().scheduleDelayedAccept(tt9.getInputMode().getAutoAcceptTimeout()); // restart the timer

		int[] next = getNextStep(tt9);
		if (next != null) {
			tt9.setInputMode(next[0], next[1], next[2] == 1);
		}

		tt9.forceShowWindow();
		return true;
	}


	/**
	 * Builds the list of cycle steps valid for the current field and language, skipping modes the
	 * field disallows and the En/EN cases for languages without upper case.
	 */
	private ArrayList<int[]> getAvailableSteps(@NonNull TraditionalT9 tt9) {
		ArrayList<Integer> allowed = tt9.getAllowedInputModes();
		boolean hasUpperCase = tt9.getLanguage() != null && tt9.getLanguage().hasUpperCase();

		ArrayList<int[]> steps = new ArrayList<>();
		for (int[] step : STEPS) {
			if (!allowed.contains(step[0])) {
				continue;
			}
			if (step[0] == InputMode.MODE_ABC && step[1] != InputMode.CASE_LOWER && !hasUpperCase) {
				continue;
			}
			steps.add(step);
		}

		return steps;
	}


	private int getCurrentStepIndex(@NonNull TraditionalT9 tt9, @NonNull ArrayList<int[]> steps) {
		InputMode mode = tt9.getInputMode();
		int modeId = mode.getId();
		int selectedCase = mode.getSelectedTextCase();

		for (int i = 0; i < steps.size(); i++) {
			int[] step = steps.get(i);
			if (step[0] != modeId) {
				continue;
			}
			if (modeId != InputMode.MODE_ABC || step[1] == selectedCase) {
				return i;
			}
		}

		return -1;
	}


	@Nullable
	private int[] getNextStep(@NonNull TraditionalT9 tt9) {
		ArrayList<int[]> steps = getAvailableSteps(tt9);
		if (steps.isEmpty()) {
			return null;
		}

		// Re-sync our tracked position from the mode ONLY when it no longer matches — i.e. the
		// mode was changed outside this cycle (new field, another command), or the step list
		// changed. Within the cycle we trust our own index, so a field that forces the text
		// case can't drag us back to the "en" step and trap us in the ABC sub-steps.
		if (
			cycleIndex < 0
			|| cycleIndex >= steps.size()
			|| steps.get(cycleIndex)[0] != tt9.getInputMode().getId()
		) {
			cycleIndex = getCurrentStepIndex(tt9, steps);
		}

		cycleIndex = (cycleIndex + 1) % steps.size(); // -1 (unknown) advances to 0, starting the cycle
		return steps.get(cycleIndex);
	}
}
