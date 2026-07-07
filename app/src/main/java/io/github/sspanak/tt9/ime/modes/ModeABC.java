package io.github.sspanak.tt9.ime.modes;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.ime.modes.helpers.AutoSpace;
import io.github.sspanak.tt9.ime.modes.helpers.AutoTextCase;
import io.github.sspanak.tt9.ime.modes.helpers.Sequences;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.chars.Characters;

class ModeABC extends InputMode {
	private final ArrayList<ArrayList<String>> KEY_CHARACTERS = new ArrayList<>();

	private boolean shouldSelectNextLetter = false;

	// KT9 fork: the selected ABC sub-mode (en/En/EN), stored as a text case. Drives both the status
	// label and the typing case. Defaults to en (all lowercase).
	private int labelCase = CASE_LOWER;

	// text analysis
	@NonNull private final AutoSpace autoSpace;
	@NonNull private final AutoTextCase autoTextCase;
	@Nullable private final InputType inputType;
	private final int textFieldTextCase;

	@Override public int getId() { return MODE_ABC; }
	@Override @NonNull public String toAccessibilityString(@NonNull Context c) { return language.getName() + ", " + language.getAbcString(); }


	protected ModeABC(@NonNull SettingsStore settings, @NonNull Language lang, @Nullable InputType inputType) {
		super(settings, inputType);
		autoSpace = new AutoSpace(settings);
		autoTextCase = new AutoTextCase(settings, new Sequences(), inputType);
		this.inputType = inputType;
		textFieldTextCase = inputType == null ? CASE_UNDEFINED : inputType.determineTextCase();

		setLanguage(lang);
		defaultTextCase();
	}


	@Override
	public boolean onBackspace() {
		if (!suggestions.isEmpty()) {
			reset();
		}

		return false;
	}


	@Override
	public boolean onNumber(int number, boolean hold, int repeat, @NonNull String[] s) {
		return onNumber(number, hold, repeat);
	}


	private boolean onNumber(int number, boolean hold, int repeat) {
		if (hold) {
			reset();
			autoAcceptTimeout = 0;
			digitSequence = String.valueOf(number);
			shouldSelectNextLetter = false;

			ArrayList<String> newSuggestions = new ArrayList<>(1);
			newSuggestions.add(language.getKeyNumeral(number));
			suggestions = newSuggestions;
		} else if (repeat > 0 && !suggestions.isEmpty()) {
			// KT9 fork: classic multi-tap for letters — cycle to the next letter and (re)start the accept
			// timer, so the letter commits after the ABC timeout and a later tap starts a fresh letter
			// (e.g. tap A, wait, tap A -> "aa"). The "1" key opens the punctuation panel, which must stay
			// open until the user picks a character, so it never auto-commits.
			autoAcceptTimeout = number == 1 ? -1 : settings.getAutoAcceptTimeoutAbc();
			shouldSelectNextLetter = true;
		} else {
			reset();
			// KT9 fork: classic multi-tap — start the accept timer so the first letter commits after the
			// ABC timeout; tapping the same key again before then cycles to the next letter. The "1" key
			// opens the punctuation panel, which must stay open (no auto-commit) until the user picks.
			autoAcceptTimeout = number == 1 ? -1 : settings.getAutoAcceptTimeoutAbc();
			digitSequence = String.valueOf(number);
			shouldSelectNextLetter = false;

			// KT9 fork: show only the key's letters to pick from, not the digit. The number is still
			// available by holding the key.
			ArrayList<String> newSuggestions = new ArrayList<>(KEY_CHARACTERS.size() > number ? KEY_CHARACTERS.get(number) : settings.getOrderedKeyChars(language, number));
			suggestions = newSuggestions;
		}

		return true;
	}


	/******** TEXT CASE ********/
	@Override
	protected String adjustSuggestionTextCase(String word, int newTextCase) {
		if (language.hasUpperCase()) {
			return newTextCase == CASE_LOWER ? word.toLowerCase(language.getLocale()) : word.toUpperCase(language.getLocale());
		} else {
			return word;
		}
	}


	@Override
	public void determineNextWordTextCase(@Nullable String beforeCursor, int nextDigit) {
		// KT9 fork: the case is driven entirely by which ABC sub-mode is selected (labelCase), so it
		// is consistent no matter how ABC was entered (via "#" or as the default mode):
		//   en (CASE_LOWER)      -> always lowercase
		//   EN (CASE_UPPER)      -> always uppercase
		//   En (CASE_CAPITALIZE) -> auto sentence-casing (capital at sentence start, lower otherwise)
		if (labelCase == CASE_LOWER) {
			textCase = CASE_LOWER;
		} else if (labelCase == CASE_UPPER) {
			textCase = CASE_UPPER;
		} else {
			textCase = autoTextCase.determineNextLetterTextCase(language, textFieldTextCase, beforeCursor);
		}
	}


	/**
	 * KT9 fork: force the ABC sub-mode (en/En/EN) used by the "#" cycle. The selected case is stored
	 * in labelCase, which both the status label and determineNextWordTextCase read from.
	 */
	@Override
	public void applyForcedTextCase(int newTextCase, boolean lock) {
		if (setTextCase(newTextCase)) {
			labelCase = newTextCase;
		}
	}


	@Override
	public int getSelectedTextCase() {
		return labelCase;
	}


	@Override
	public boolean nextTextCase(@Nullable String currentWord, int displayTextCase) {
		if (suggestions.isEmpty()) {
			return super.nextTextCase(currentWord, displayTextCase);
		}

		for (int newTextCase : allowedTextCases) {
			if (newTextCase != textCase && newTextCase != InputMode.CASE_CAPITALIZE) {
				textCase = newTextCase;
				return true;
			}
		}

		return false;
	}


	@Override
	public void skipNextTextCaseDetection() {
		autoTextCase.skipNext();
	}


	/******** AUTO-SPACE ********/
	@Override
	public boolean shouldAddTrailingSpace(@NonNull String previousChars, @NonNull String nextChars, boolean isWordAcceptedManually, int nextKey) {
		return autoSpace.shouldAddTrailingSpace(inputType, this, previousChars, nextChars, isWordAcceptedManually, nextKey);
	}


	@Override
	public boolean shouldAddPrecedingSpace(@NonNull String previousChars) {
		return autoSpace.shouldAddBeforePunctuation(inputType, previousChars);
	}


	@Override
	public boolean shouldDeletePrecedingSpace(@NonNull String previousChars) {
		return autoSpace.shouldDeletePrecedingSpace(inputType, previousChars);
	}


	private void refreshSuggestions() {
		if (digitSequence.isEmpty()) {
			suggestions = new ArrayList<>();
		} else {
			onNumber(digitSequence.charAt(0) - '0', false, 0);
		}
	}


	@Override
	public boolean setLanguage(@Nullable Language newLanguage) {
		if (newLanguage != null && !newLanguage.hasABC()) {
			return false;
		}

		super.setLanguage(newLanguage);

		autoSpace.setLanguage(newLanguage);

		allowedTextCases.clear();
		allowedTextCases.add(CASE_LOWER);
		if (language.hasUpperCase()) {
			if (settings.getAutoTextCaseAbc() && inputType != null && !inputType.isSpecialized()) {
				allowedTextCases.add(CASE_CAPITALIZE);
			}
			allowedTextCases.add(CASE_UPPER);
		}

		KEY_CHARACTERS.clear();
		if (isEmailMode) {
			// Asian punctuation can not be used in email addresses, so we need to use the English locale.
			Language lang = LanguageKind.isCJK(language) ? LanguageCollection.getByLocale("en") : language;
			KEY_CHARACTERS.add(Characters.orderByList(Characters.Email, settings.getOrderedKeyChars(lang, 0), true));
			KEY_CHARACTERS.add(Characters.orderByList(Characters.Email, settings.getOrderedKeyChars(lang, 1), true));
		}

		refreshSuggestions();
		shouldSelectNextLetter = true; // do not accept any previous suggestions after loading the new ones

		return true;
	}


	@Override
	public void setSequence(@NonNull String sequence) {
		super.setSequence(sequence);
		refreshSuggestions();
		shouldSelectNextLetter = true;
	}


	@Override public void onAcceptSuggestion(@NonNull String w) {
		reset();
	}


	@Override
	public boolean shouldAcceptPreviousSuggestion(String word) {
		return
			!shouldSelectNextLetter
			&& word != null && !word.isEmpty()
			&& !Characters.PLACEHOLDER.equals(word);
	}


	@Override
	public boolean shouldSelectNextSuggestion() {
		return shouldSelectNextLetter;
	}


	@Override
	public void reset() {
		super.reset();
		digitSequence = "";
		shouldSelectNextLetter = false;
	}


	@NonNull
	@Override
	public String toString() {
		// KT9 fork: show the language code in the "#"-selected case, e.g. en / En / EN.
		return new Text(language, language.getCode()).toTextCase(labelCase);
	}
}
