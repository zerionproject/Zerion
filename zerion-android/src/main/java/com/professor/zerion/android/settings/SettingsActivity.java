package com.professor.zerion.android.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceFragmentCompat.OnPreferenceStartFragmentCallback;

import static android.content.Intent.ACTION_MANAGE_NETWORK_USAGE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SettingsActivity extends ZerionActivity
		implements OnPreferenceStartFragmentCallback {

	static final String EXTRA_THEME_CHANGE = "themeChange";

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		setContentView(R.layout.activity_settings);

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			getWindow().setStatusBarColor(getResources().getColor(R.color.zerion_dark_bg));
		}

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			getWindow().setNavigationBarColor(getResources().getColor(R.color.zerion_dark_bg));
		}

		androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setHomeButtonEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		Intent i = getIntent();
		Bundle extras = i.getExtras();
		if (bundle == null && extras != null &&
				extras.getBoolean(EXTRA_THEME_CHANGE, false)) {
			FragmentManager fragmentManager = getSupportFragmentManager();
			showNextFragment(fragmentManager, new DisplayFragment());
		} else if (bundle == null &&
				ACTION_MANAGE_NETWORK_USAGE.equals(i.getAction())) {
			FragmentManager fragmentManager = getSupportFragmentManager();
			showNextFragment(fragmentManager, new ConnectionsFragment());
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return false;
	}

	@Override
	public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller,
			Preference pref) {
		FragmentManager fragmentManager = getSupportFragmentManager();
		FragmentFactory fragmentFactory = fragmentManager.getFragmentFactory();
		Fragment fragment = fragmentFactory
				.instantiate(getClassLoader(), pref.getFragment());
		fragment.setTargetFragment(caller, 0);
		showNextFragment(fragmentManager, fragment);
		return true;
	}

	private void showNextFragment(FragmentManager fragmentManager, Fragment f) {
		fragmentManager.beginTransaction()
				.setCustomAnimations(R.anim.step_next_in,
						R.anim.step_previous_out, R.anim.step_previous_in,
						R.anim.step_next_out)
				.replace(R.id.fragmentContainer, f)
				.addToBackStack(null)
				.commit();
	}

	static void enableAndPersist(Preference pref) {
		if (!pref.isEnabled()) {
			pref.setEnabled(true);
			pref.setPersistent(true);
		}
	}

}
