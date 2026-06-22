package com.professor.zerion.android.login;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionService;
import com.professor.zerion.android.account.WelcomeActivity;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import com.professor.zerion.android.login.StartupViewModel.State;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static com.professor.zerion.android.login.StartupViewModel.State.SIGNED_IN;
import static com.professor.zerion.android.login.StartupViewModel.State.SIGNED_OUT;
import static com.professor.zerion.android.login.StartupViewModel.State.STARTED;
import static com.professor.zerion.android.login.StartupViewModel.State.STARTING;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class StartupActivity extends BaseActivity implements
		BaseFragmentListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private StartupViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(StartupViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		com.professor.zerion.android.ZerionService.cancelPendingExit();
		getWindow().addFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		overridePendingTransition(R.anim.fade_in, R.anim.fade_out);

		setContentView(R.layout.activity_fragment_container);

		viewModel.getAccountDeleted().observeEvent(this, deleted -> {
			if (deleted) onAccountDeleted();
		});
		viewModel.getState().observe(this, this::onStateChanged);
		viewModel.checkAccountExistsAsync(exists -> {
			if (!exists) {
				viewModel.deleteAccount();
				onAccountDeleted();
			}
		});
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.clearSignInNotification();
	}

	@Override
	@SuppressLint("MissingSuperCall")
	public void onBackPressed() {
		moveTaskToBack(true);
	}

	private void onStateChanged(State state) {
		if (state == SIGNED_OUT) {
			showInitialFragment(new PasswordFragment());
		} else if (state == SIGNED_IN || state == STARTING) {
			startService(new Intent(this, ZerionService.class));
			showNextFragment(new OpenDatabaseFragment());
		} else if (state == STARTED) {
			setResult(RESULT_OK);
			supportFinishAfterTransition();
			overridePendingTransition(R.anim.screen_new_in,
					R.anim.screen_old_out);
		}
	}

	private void onAccountDeleted() {
		setResult(RESULT_CANCELED);
		finish();
		Intent i = new Intent(this, WelcomeActivity.class);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP |
				FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_TASK_ON_HOME);
		startActivity(i);
	}

	@Override
	public void runOnDbThread(Runnable runnable) {
		throw new UnsupportedOperationException();
	}

}
