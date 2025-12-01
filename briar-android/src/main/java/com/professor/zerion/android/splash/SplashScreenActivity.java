package com.professor.zerion.android.splash;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.transition.Fade;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static androidx.preference.PreferenceManager.setDefaultValues;
import static java.lang.System.currentTimeMillis;
import static java.util.logging.Logger.getLogger;
import static com.professor.zerion.android.BriarApplication.ENTRY_ACTIVITY;
import static com.professor.zerion.android.TestingConstants.EXPIRY_DATE;
import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SplashScreenActivity extends BaseActivity {

	private static final Logger LOG =
			getLogger(SplashScreenActivity.class.getName());

	@Inject
	protected AccountManager accountManager;
	@Inject
	protected AndroidExecutor androidExecutor;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		getWindow().setExitTransition(new Fade());
		setPreferencesDefaults();
		setContentView(R.layout.splash);

		// Apply Matrix-style logo decode animation
		applyLogoDecodeEffect();

		if (accountManager.hasDatabaseKey()) {
			startNextActivity(ENTRY_ACTIVITY);
			finish();
		} else {
			int duration =
					getResources().getInteger(R.integer.splashScreenDuration);
			new Handler().postDelayed(() -> {
				if (IS_DEBUG_BUILD && currentTimeMillis() >= EXPIRY_DATE) {
					LOG.info("Expired");
					startNextActivity(ExpiredActivity.class);
				} else {
					startNextActivity(ENTRY_ACTIVITY);
				}
				supportFinishAfterTransition();
			}, duration);
		}
	}

	/**
	 * Apply Matrix-style "decode" effect to logo.
	 * Logo scales up and fades in as if being decoded from the digital rain.
	 */
	private void applyLogoDecodeEffect() {
		View logo = findViewById(R.id.logoView);
		if (logo == null) return;

		// Start invisible and scaled down
		logo.setAlpha(0f);
		logo.setScaleX(0.5f);
		logo.setScaleY(0.5f);

		// Fade in animation (0 -> 1 over 800ms)
		ObjectAnimator fadeIn = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
		fadeIn.setDuration(800);
		fadeIn.setInterpolator(new AccelerateDecelerateInterpolator());

		// Scale up animation (0.5 -> 1 over 800ms)
		ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0.5f, 1f);
		scaleX.setDuration(800);
		scaleX.setInterpolator(new AccelerateDecelerateInterpolator());

		ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0.5f, 1f);
		scaleY.setDuration(800);
		scaleY.setInterpolator(new AccelerateDecelerateInterpolator());

		// Start animations with slight delay for dramatic effect
		new Handler().postDelayed(() -> {
			fadeIn.start();
			scaleX.start();
			scaleY.start();
		}, 300);
	}

	private void startNextActivity(Class<? extends Activity> activityClass) {
		Intent i = new Intent(this, activityClass);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
	}

	private void setPreferencesDefaults() {
		androidExecutor.runOnBackgroundThread(
				() -> setDefaultValues(SplashScreenActivity.this,
						R.xml.panic_preferences, false));
	}

	// Don't show any warnings here
	@Override
	public boolean shouldAllowTap() {
		return true;
	}
}
