package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.Localizer;
import com.professor.zerion.android.util.UiUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static com.professor.zerion.android.ZerionApplication.ENTRY_ACTIVITY;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.SIGN_OUT_URI;
import static com.professor.zerion.android.settings.SettingsActivity.EXTRA_THEME_CHANGE;

@NotNullByDefault
public class DisplayFragment extends Fragment {

	public static final String PREF_LANGUAGE = "pref_key_language";
	public static final String PREF_THEME = "pref_key_theme";

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	private MaterialCardView languageCard;
	private TextView languageValue;
	private MaterialCardView themeCard;
	private TextView themeValue;

	private String[] languageTags;
	private CharSequence[] languageEntries;
	private String currentLanguage = "default";
	private String currentTheme;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_display, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);


		languageCard = view.findViewById(R.id.language_card);
		languageValue = view.findViewById(R.id.language_value);
		themeCard = view.findViewById(R.id.theme_card);
		themeValue = view.findViewById(R.id.theme_value);


		loadCurrentSettings();


		setupLanguageEntries();


		languageCard.setOnClickListener(v -> showLanguageDialog());
		themeCard.setOnClickListener(v -> showThemeDialog());
	}

	private void loadCurrentSettings() {
		currentLanguage = uiPrefs.getString(PREF_LANGUAGE, "default");
		updateLanguageDisplay();

		currentTheme = uiPrefs.getString(PREF_THEME, getString(R.string.pref_theme_dark_value));
		updateThemeDisplay();
	}

	private void setupLanguageEntries() {
		String[] tags = getResources().getStringArray(R.array.pref_language_values);
		List<CharSequence> entries = new ArrayList<>(tags.length);
		List<String> entryValues = new ArrayList<>(tags.length);

		for (String tag : tags) {
			if (tag.equals("default")) {
				entries.add(getString(R.string.pref_language_default));
				entryValues.add(tag);
				continue;
			}
			Locale locale = Localizer.getLocaleFromTag(tag);
			if (locale == null) continue;

			String nativeName = locale.getDisplayName(locale);

			if (nativeName.equals(tag)) {
				String tmp = locale.getDisplayLanguage(Locale.ENGLISH);
				if (!tmp.isEmpty() && !tmp.equals(nativeName)) {
					nativeName = tmp;
				}
			}

			entries.add("\u200E" + nativeName.substring(0, 1).toUpperCase()
					+ nativeName.substring(1));
			entryValues.add(tag);
		}

		languageEntries = entries.toArray(new CharSequence[0]);
		languageTags = entryValues.toArray(new String[0]);
	}

	private void updateLanguageDisplay() {
		if (currentLanguage.equals("default")) {
			languageValue.setText(R.string.pref_language_default);
		} else {
			Locale locale = Localizer.getLocaleFromTag(currentLanguage);
			if (locale != null) {
				String nativeName = locale.getDisplayName(locale);
				languageValue.setText("\u200E" + nativeName.substring(0, 1).toUpperCase()
						+ nativeName.substring(1));
			} else {
				languageValue.setText(R.string.pref_language_default);
			}
		}
	}

	private void updateThemeDisplay() {
		String systemValue = getString(R.string.pref_theme_system_value);
		String lightValue = getString(R.string.pref_theme_light_value);
		String darkValue = getString(R.string.pref_theme_dark_value);

		if (currentTheme.equals(systemValue)) {
			themeValue.setText(R.string.pref_theme_system);
		} else if (currentTheme.equals(lightValue)) {
			themeValue.setText(R.string.pref_theme_light);
		} else if (currentTheme.equals(darkValue)) {
			themeValue.setText(R.string.pref_theme_dark);
		}
	}

	private void showLanguageDialog() {
		int selectedIndex = 0;
		for (int i = 0; i < languageTags.length; i++) {
			if (languageTags[i].equals(currentLanguage)) {
				selectedIndex = i;
				break;
			}
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_language_title)
				.setSingleChoiceItems(languageEntries, selectedIndex, (dialog, which) -> {
					String newLanguage = languageTags[which];
					if (!currentLanguage.equals(newLanguage)) {
						showLanguageChangeConfirmation(newLanguage);
					}
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showLanguageChangeConfirmation(String newLanguage) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_language_title)
				.setMessage(R.string.pref_language_changed)
				.setPositiveButton(R.string.sign_out_button, (d, i) -> {
					uiPrefs.edit()
							.putString(PREF_LANGUAGE, newLanguage)
							.apply();
					Intent intent = new Intent(getContext(), ENTRY_ACTIVITY);
					intent.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
					intent.setData(SIGN_OUT_URI);
					requireActivity().startActivity(intent);
					requireActivity().finish();
				})
				.setNegativeButton(R.string.cancel, null)
				.setCancelable(false)
				.show();
	}

	private void showThemeDialog() {
		String[] themeEntries = getResources().getStringArray(R.array.pref_theme_entries);
		String[] themeValues = getResources().getStringArray(R.array.pref_theme_values);

		int selectedIndex = 0;
		for (int i = 0; i < themeValues.length; i++) {
			if (themeValues[i].equals(currentTheme)) {
				selectedIndex = i;
				break;
			}
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_theme_title)
				.setSingleChoiceItems(themeEntries, selectedIndex, (dialog, which) -> {
					String newTheme = themeValues[which];
					if (!currentTheme.equals(newTheme)) {
						onThemeChanged(newTheme);
					}
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void onThemeChanged(String newTheme) {
		uiPrefs.edit()
				.putString(PREF_THEME, newTheme)
				.apply();

		FragmentActivity activity = requireActivity();
		UiUtils.setTheme(activity, newTheme);


		Intent intent = new Intent(getActivity(), ENTRY_ACTIVITY);
		intent.setFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);

		intent = new Intent(getActivity(), activity.getClass());
		intent.putExtra(EXTRA_THEME_CHANGE, true);
		startActivity(intent);
		activity.finish();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.display_settings_title);
	}

}
