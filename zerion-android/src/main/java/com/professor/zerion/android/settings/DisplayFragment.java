package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
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

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static com.professor.zerion.android.ZerionApplication.ENTRY_ACTIVITY;

import static com.professor.zerion.android.settings.ChatPreferences.*;
import static com.professor.zerion.android.settings.SettingsActivity.EXTRA_THEME_CHANGE;

@NotNullByDefault
public class DisplayFragment extends Fragment {

	public static final String PREF_LANGUAGE = "pref_key_language";
	public static final String PREF_THEME = "pref_key_theme";

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	private View languageCard;
	private TextView languageValue;
	private View themeCard;
	private TextView themeValue;
	private View appIconCard;
	private TextView appIconValue;
	private View navSizeCard;
	private TextView navSizeValue;
	private View textSizeCard;
	private TextView textSizeValue;
	private View bubbleColorCard;
	private TextView bubbleColorValue;
	private View bubbleColorPreview;

	private String[] languageTags;
	private CharSequence[] languageEntries;
	private String currentLanguage = "default";
	private String currentTheme;
	private int currentAppIcon;
	private int currentNavSize;
	private int currentTextSize;
	private int currentBubbleColor;

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
		appIconCard = view.findViewById(R.id.app_icon_card);
		appIconValue = view.findViewById(R.id.app_icon_value);
		navSizeCard = view.findViewById(R.id.nav_size_card);
		navSizeValue = view.findViewById(R.id.nav_size_value);
		textSizeCard = view.findViewById(R.id.text_size_card);
		textSizeValue = view.findViewById(R.id.text_size_value);
		bubbleColorCard = view.findViewById(R.id.bubble_color_card);
		bubbleColorValue = view.findViewById(R.id.bubble_color_value);
		bubbleColorPreview = view.findViewById(R.id.bubble_color_preview);

		loadCurrentSettings();
		setupLanguageEntries();

		languageCard.setOnClickListener(v -> showLanguageDialog());
		themeCard.setOnClickListener(v -> showThemeDialog());
		if (appIconCard != null) {
			appIconCard.setOnClickListener(v -> showAppIconDialog());
		}
		if (navSizeCard != null) {
			navSizeCard.setOnClickListener(v -> showNavSizeDialog());
		}
		textSizeCard.setOnClickListener(v -> showTextSizeDialog());
		bubbleColorCard.setOnClickListener(v -> showBubbleColorDialog());
	}

	private void loadCurrentSettings() {
		currentLanguage = uiPrefs.getString(PREF_LANGUAGE, "default");
		updateLanguageDisplay();

		currentTheme = uiPrefs.getString(PREF_THEME, getString(R.string.pref_theme_dark_value));
		updateThemeDisplay();

		SharedPreferences securePrefs = getAndroidComponent(requireContext())
				.securePreferences();
		currentAppIcon = AppIconManager.getCurrentIcon(requireContext());
		updateAppIconDisplay();

		currentNavSize = securePrefs.getInt(ChatPreferences.PREF_NAV_SIZE,
				ChatPreferences.NAV_DEFAULT);
		updateNavSizeDisplay();

		currentTextSize = securePrefs.getInt(PREF_TEXT_SIZE, TEXT_SIZE_MEDIUM);
		updateTextSizeDisplay();

		currentBubbleColor = securePrefs.getInt(PREF_BUBBLE_COLOR, BUBBLE_BLUE);
		updateBubbleColorDisplay();
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
		String amoledValue = getString(R.string.pref_theme_amoled_value);

		if (currentTheme.equals(systemValue)) {
			themeValue.setText(R.string.pref_theme_system);
		} else if (currentTheme.equals(lightValue)) {
			themeValue.setText(R.string.pref_theme_light);
		} else if (currentTheme.equals(amoledValue)) {
			themeValue.setText(R.string.pref_theme_amoled);
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
				.setPositiveButton(android.R.string.ok, (d, i) -> {
					uiPrefs.edit()
							.putString(PREF_LANGUAGE, newLanguage)
							.commit();
					Localizer.forceReinitialize(uiPrefs);
					Intent intent = new Intent(getContext(), ENTRY_ACTIVITY);
					intent.setFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
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

	private void updateAppIconDisplay() {
		int[] labels = {
				R.string.pref_app_icon_default,
				R.string.pref_app_icon_calculator,
				R.string.pref_app_icon_notes,
				R.string.pref_app_icon_weather
		};
		int idx = currentAppIcon;
		if (idx < 0 || idx >= labels.length) idx = AppIconManager.ICON_DEFAULT;
		if (appIconValue != null) appIconValue.setText(labels[idx]);
	}

	private void showAppIconDialog() {
		String[] entries = {
				getString(R.string.pref_app_icon_default),
				getString(R.string.pref_app_icon_calculator),
				getString(R.string.pref_app_icon_notes),
				getString(R.string.pref_app_icon_weather)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_app_icon_title)
				.setSingleChoiceItems(entries, currentAppIcon, (dialog, which) -> {
					if (which != currentAppIcon) {
						currentAppIcon = which;
						AppIconManager.setAppIcon(requireContext(), which);
						updateAppIconDisplay();
						android.widget.Toast.makeText(requireContext(),
								R.string.pref_app_icon_restart_hint,
								android.widget.Toast.LENGTH_SHORT).show();
					}
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void updateNavSizeDisplay() {
		int[] labels = {
				R.string.pref_nav_size_compact,
				R.string.pref_nav_size_default,
				R.string.pref_nav_size_large
		};
		int idx = currentNavSize;
		if (idx < 0 || idx >= labels.length) idx = ChatPreferences.NAV_DEFAULT;
		if (navSizeValue != null) navSizeValue.setText(labels[idx]);
	}

	private void showNavSizeDialog() {
		String[] entries = {
				getString(R.string.pref_nav_size_compact),
				getString(R.string.pref_nav_size_default),
				getString(R.string.pref_nav_size_large)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_nav_size_title)
				.setSingleChoiceItems(entries, currentNavSize, (dialog, which) -> {
					if (which != currentNavSize) {
						currentNavSize = which;
						SharedPreferences securePrefs =
								getAndroidComponent(requireContext())
										.securePreferences();
						securePrefs.edit()
								.putInt(ChatPreferences.PREF_NAV_SIZE, which)
								.apply();
						updateNavSizeDisplay();
					}
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void updateTextSizeDisplay() {
		int[] labels = {
				R.string.pref_text_size_small,
				R.string.pref_text_size_medium,
				R.string.pref_text_size_large,
				R.string.pref_text_size_extra_large
		};
		int idx = currentTextSize;
		if (idx < 0 || idx >= labels.length) idx = TEXT_SIZE_MEDIUM;
		textSizeValue.setText(labels[idx]);
	}

	private void updateBubbleColorDisplay() {
		int[] labels = {
				R.string.pref_bubble_color_blue,
				R.string.pref_bubble_color_purple,
				R.string.pref_bubble_color_green,
				R.string.pref_bubble_color_orange,
				R.string.pref_bubble_color_pink,
				R.string.pref_bubble_color_cyan
		};
		int[] colors = {
				R.color.bubble_blue,
				R.color.bubble_purple,
				R.color.bubble_green,
				R.color.bubble_orange,
				R.color.bubble_pink,
				R.color.bubble_cyan
		};
		int idx = currentBubbleColor;
		if (idx < 0 || idx >= labels.length) idx = BUBBLE_BLUE;
		bubbleColorValue.setText(labels[idx]);
		if (bubbleColorPreview.getBackground() instanceof GradientDrawable) {
			((GradientDrawable) bubbleColorPreview.getBackground())
					.setColor(requireContext().getResources()
							.getColor(colors[idx], requireContext().getTheme()));
		}
	}

	private void showTextSizeDialog() {
		String[] entries = {
				getString(R.string.pref_text_size_small),
				getString(R.string.pref_text_size_medium),
				getString(R.string.pref_text_size_large),
				getString(R.string.pref_text_size_extra_large)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_text_size_title)
				.setSingleChoiceItems(entries, currentTextSize, (dialog, which) -> {
					if (which != currentTextSize) {
						currentTextSize = which;
						SharedPreferences securePrefs = getAndroidComponent(requireContext())
								.securePreferences();
						securePrefs.edit().putInt(PREF_TEXT_SIZE, which).apply();
						updateTextSizeDisplay();
					}
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showBubbleColorDialog() {
		String[] entries = {
				getString(R.string.pref_bubble_color_blue),
				getString(R.string.pref_bubble_color_purple),
				getString(R.string.pref_bubble_color_green),
				getString(R.string.pref_bubble_color_orange),
				getString(R.string.pref_bubble_color_pink),
				getString(R.string.pref_bubble_color_cyan)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_bubble_color_title)
				.setSingleChoiceItems(entries, currentBubbleColor, (dialog, which) -> {
					if (which != currentBubbleColor) {
						currentBubbleColor = which;
						SharedPreferences securePrefs = getAndroidComponent(requireContext())
								.securePreferences();
						securePrefs.edit().putInt(PREF_BUBBLE_COLOR, which).apply();
						updateBubbleColorDisplay();
					}
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.display_settings_title);
	}

}
