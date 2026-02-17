package com.professor.zerion.android;

import android.content.Intent;
import android.os.Bundle;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import com.professor.zerion.android.fragment.ErrorFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult;
import static com.professor.zerion.android.ZerionService.EXTRA_START_RESULT;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class StartupFailureActivity extends BaseActivity implements
		BaseFragmentListener {

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		setContentView(R.layout.activity_fragment_container);
		handleIntent(getIntent());
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	private void handleIntent(Intent i) {
		String resultName = i.getStringExtra(EXTRA_START_RESULT);
		if (resultName == null) {
			finish();
			return;
		}
		StartResult result;
		try {
			result = StartResult.valueOf(resultName);
		} catch (IllegalArgumentException e) {
			finish();
			return;
		}

		int errorRes;
		switch (result) {
			case CLOCK_ERROR:
				errorRes = R.string.startup_failed_clock_error;
				break;
			case DATA_TOO_OLD_ERROR:
				errorRes = R.string.startup_failed_data_too_old_error;
				break;
			case DATA_TOO_NEW_ERROR:
				errorRes = R.string.startup_failed_data_too_new_error;
				break;
			case DB_ERROR:
				errorRes = R.string.startup_failed_db_error;
				break;
			case SERVICE_ERROR:
				errorRes = R.string.startup_failed_service_error;
				break;
			default:
				throw new IllegalArgumentException();
		}
		showInitialFragment(ErrorFragment.newInstance(getString(errorRes)));
	}

	@Override
	public void runOnDbThread(@NonNull Runnable runnable) {
		throw new UnsupportedOperationException();
	}
}
