package com.professor.zerion.android.activity;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

import com.professor.zerion.R;
import android.content.SharedPreferences;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.DestroyableContext;
import com.professor.zerion.android.Localizer;
import com.professor.zerion.android.controller.ActivityLifecycleController;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.fragment.ScreenFilterDialogFragment;
import com.professor.zerion.android.util.UiUtils;
import com.professor.zerion.android.widget.TapSafeFrameLayout;
import com.professor.zerion.android.vault.ui.IncognitoInputHelper;
import com.professor.zerion.android.widget.TapSafeFrameLayout.OnTapFilteredListener;
import com.professor.zerion.android.api.ScreenFilterMonitor;
import com.professor.zerion.android.api.ScreenFilterMonitor.AppDetails;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Intent;
import static androidx.lifecycle.Lifecycle.State.STARTED;
import static java.util.Collections.emptyList;
import static com.professor.zerion.android.settings.DisplayFragment.PREF_THEME;
import static com.professor.zerion.android.util.UiUtils.hideSoftKeyboard;
import static com.professor.zerion.android.util.UiUtils.isAmoledTheme;
import static com.professor.zerion.android.util.UiUtils.showFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class BaseActivity extends AppCompatActivity
		implements DestroyableContext, OnTapFilteredListener {

	@Inject
	protected ScreenFilterMonitor screenFilterMonitor;

	protected ActivityComponent activityComponent;
	protected com.professor.zerion.android.security.SecurityManager securityManager;

	private final List<ActivityLifecycleController> lifecycleControllers =
			new ArrayList<>();
	private boolean destroyed = false;

	@Nullable
	private Toolbar toolbar = null;
	private boolean searchedForToolbar = false;

	public abstract void injectActivity(ActivityComponent component);

	public void addLifecycleController(ActivityLifecycleController alc) {
		lifecycleControllers.add(alc);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		AndroidComponent applicationComponent =
				((ZerionApplication) getApplication()).getApplicationComponent();
		activityComponent = DaggerActivityComponent.builder()
				.androidComponent(applicationComponent)
				.activityModule(getActivityModule())
				.build();
		injectActivity(activityComponent);

		securityManager = applicationComponent.securityManager();

		SharedPreferences uiPrefs = applicationComponent.uiPreferences();
		String theme = uiPrefs.getString(PREF_THEME, "");
		if (isAmoledTheme(theme, this)) {
			getTheme().applyStyle(R.style.AmoledOverlay, true);
		}

		int accentOverlay = com.professor.zerion.android.settings
				.ChatPreferences.getAccentOverlayStyle(this);
		if (accentOverlay != 0) {
			getTheme().applyStyle(accentOverlay, true);
		}

		int hardenedResult =
				com.professor.zerion.android.security
						.HardenedModeEvaluator.evaluate(uiPrefs);
		if (hardenedResult != com.professor.zerion.android.security
				.SecureBootGuard.RESULT_OK
				&& !(this instanceof com.professor.zerion.android.security
						.HardenedBlockActivity)) {
			super.onCreate(state);
			Intent blockIntent = new Intent(this,
					com.professor.zerion.android.security
							.HardenedBlockActivity.class);
			blockIntent.putExtra(
					com.professor.zerion.android.security
							.HardenedBlockActivity.EXTRA_RESULT_CODE,
					hardenedResult);
			blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
					| Intent.FLAG_ACTIVITY_CLEAR_TASK);
			startActivity(blockIntent);
			finish();
			return;
		}

		super.onCreate(state);

		androidx.activity.EdgeToEdge.enable(this);

		securityManager.applyScreenshotProtection(this, forceScreenshotProtection());

		if (SDK_INT >= 31) getWindow().setHideOverlayWindows(true);

		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityCreate(this);
		}
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(
				Localizer.getInstance().applyLocaleToContext(base));
	}

	public ActivityComponent getActivityComponent() {
		return activityComponent;
	}

	protected ActivityModule getActivityModule() {
		return new ActivityModule(this);
	}

	@Override
	protected void onStart() {
		super.onStart();
		securityManager.applyScreenshotProtection(this, forceScreenshotProtection());
		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityStart();
		}
		protectToolbar();
		ScreenFilterDialogFragment f = findDialogFragment();
		if (f != null) f.setDismissListener(this::protectToolbar);
	}

	@Nullable
	private ScreenFilterDialogFragment findDialogFragment() {
		Fragment f = getSupportFragmentManager().findFragmentByTag(
				ScreenFilterDialogFragment.TAG);
		return (ScreenFilterDialogFragment) f;
	}

	@Override
	protected void onResume() {
		super.onResume();
		enforceSecureInputs();
	}

	private void enforceSecureInputs() {
		View decorView = getWindow().getDecorView();
		IncognitoInputHelper.enforceSecureInputsOnViewTree(decorView);
	}

	protected boolean forceScreenshotProtection() {
		return false;
	}

	@Override
	protected void onPause() {
		super.onPause();
	}

	@Override
	protected void onStop() {
		super.onStop();
		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityStop();
		}
	}

	protected void showInitialFragment(BaseFragment f) {
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, f, f.getUniqueTag())
				.commit();
	}

	public void showNextFragment(BaseFragment f) {
		if (!getLifecycle().getCurrentState().isAtLeast(STARTED)) return;
		showFragment(getSupportFragmentManager(), f, f.getUniqueTag());
	}

	private boolean showScreenFilterWarning() {
		if (((ZerionApplication) getApplication()).isInstrumentationTest()) {
			return false;
		}
		ScreenFilterDialogFragment f = findDialogFragment();
		if (f != null && f.isVisible()) return false;
		Collection<AppDetails> apps;
		if (SDK_INT <= 29) {
			apps = screenFilterMonitor.getApps();
			if (apps.isEmpty()) return true;
		} else {
			apps = emptyList();
		}
		FragmentManager fm = getSupportFragmentManager();
		if (!fm.isStateSaved()) {
			f = ScreenFilterDialogFragment.newInstance(apps);
			f.setDismissListener(this::protectToolbar);
			View focus = getCurrentFocus();
			if (focus != null) hideSoftKeyboard(focus);
			f.show(fm, ScreenFilterDialogFragment.TAG);
		}
		return false;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		destroyed = true;
		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityDestroy();
		}
	}

	@Override
	public void runOnUiThreadUnlessDestroyed(Runnable r) {
		runOnUiThread(() -> {
			if (!destroyed && !isFinishing()) r.run();
		});
	}

	@UiThread
	public void handleException(Exception e) {
		supportFinishAfterTransition();
	}

	private View makeTapSafeWrapper(View v) {
		TapSafeFrameLayout wrapper = new TapSafeFrameLayout(this);
		wrapper.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		wrapper.setOnTapFilteredListener(this);
		wrapper.addView(v);
		return wrapper;
	}

	private void protectToolbar() {
		findToolbar();
		if (toolbar != null) {
			boolean filter;
			if (SDK_INT <= 29) {
				filter = !screenFilterMonitor.getApps().isEmpty();
			} else {
				filter = true;
			}
			UiUtils.setFilterTouchesWhenObscured(toolbar, filter);
		}
	}

	private void findToolbar() {
		if (searchedForToolbar) return;
		View decorView = getWindow().getDecorView();
		if (decorView instanceof ViewGroup)
			toolbar = findToolbar((ViewGroup) decorView);
		searchedForToolbar = true;
	}

	@Nullable
	private Toolbar findToolbar(ViewGroup vg) {
		if (vg instanceof TapSafeFrameLayout) return null;
		for (int i = 0, len = vg.getChildCount(); i < len; i++) {
			View child = vg.getChildAt(i);
			if (child instanceof Toolbar) return (Toolbar) child;
			if (child instanceof ViewGroup) {
				Toolbar toolbar = findToolbar((ViewGroup) child);
				if (toolbar != null) return toolbar;
			}
		}
		return null;
	}

	@Override
	public void setContentView(@LayoutRes int layoutRes) {
		setContentView(getLayoutInflater().inflate(layoutRes, null));
	}

	@Override
	public void setContentView(View v) {
		super.setContentView(makeTapSafeWrapper(v));
	}

	@Override
	public void setContentView(View v, LayoutParams layoutParams) {
		super.setContentView(makeTapSafeWrapper(v), layoutParams);
	}

	@Override
	public void addContentView(View v, LayoutParams layoutParams) {
		super.addContentView(makeTapSafeWrapper(v), layoutParams);
	}

	@Override
	public boolean shouldAllowTap() {
		return showScreenFilterWarning();
	}
}
