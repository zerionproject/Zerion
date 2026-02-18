package com.professor.zerion.android.splash;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.Fade;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static androidx.preference.PreferenceManager.setDefaultValues;
import static com.professor.zerion.android.ZerionApplication.ENTRY_ACTIVITY;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SplashScreenActivity extends BaseActivity {

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

		getWindow().setFlags(
				WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE
		);
		getWindow().setExitTransition(new Fade());
		androidExecutor.runOnBackgroundThread(() ->
				setDefaultValues(this, R.xml.panic_preferences, false));

		if (accountManager.hasDatabaseKey()) {
			startNextActivity(ENTRY_ACTIVITY);
			finish();
		} else {
			setContentView(R.layout.splash);

			applyLogoDecodeEffect();

			int duration =
					getResources().getInteger(R.integer.splashScreenDuration);
			mainHandler.postDelayed(() -> {
				if (isFinishing()) return;

					startNextActivity(ENTRY_ACTIVITY);
				supportFinishAfterTransition();
			}, duration);
		}
	}

	private void applyLogoDecodeEffect() {
		View logo = findViewById(R.id.logoView);
		if (logo == null) return;

		logo.setAlpha(0f);
		logo.setScaleX(0.8f);
		logo.setScaleY(0.8f);

		ObjectAnimator fadeIn = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
		fadeIn.setDuration(1000);
		fadeIn.setInterpolator(new AccelerateDecelerateInterpolator());

		ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0.8f, 1f);
		scaleX.setDuration(1000);
		scaleX.setInterpolator(new AccelerateDecelerateInterpolator());

		ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0.8f, 1f);
		scaleY.setDuration(1000);
		scaleY.setInterpolator(new AccelerateDecelerateInterpolator());

		AnimatorSet animatorSet = new AnimatorSet();
		animatorSet.playTogether(fadeIn, scaleX, scaleY);
		animatorSet.setStartDelay(300);
		animatorSet.start();
	}

	private void startNextActivity(Class<? extends Activity> activityClass) {
		Intent i = new Intent(this, activityClass);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
		overridePendingTransition(0, 0);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		mainHandler.removeCallbacksAndMessages(null);
	}

	@Override
	public boolean shouldAllowTap() {
		return true;
	}
}
