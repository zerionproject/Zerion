package com.professor.zerion.android.account;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Bundle;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static com.professor.zerion.android.BriarApplication.ENTRY_ACTIVITY;
import static com.professor.zerion.android.account.SetupViewModel.State.AUTHOR_NAME;
import static com.professor.zerion.android.account.SetupViewModel.State.CREATED;
import static com.professor.zerion.android.account.SetupViewModel.State.DOZE;
import static com.professor.zerion.android.account.SetupViewModel.State.FAILED;
import static com.professor.zerion.android.account.SetupViewModel.State.SET_PASSWORD;
import static com.professor.zerion.android.util.UiUtils.setInputStateAlwaysVisible;
import static com.professor.zerion.android.util.UiUtils.setInputStateHidden;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SetupActivity extends BaseActivity
		implements BaseFragmentListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private SetupViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);

		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(SetupViewModel.class);
		viewModel.getState().observeEvent(this, this::onStateChanged);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		// fade-in after splash screen instead of default animation
		overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
		setContentView(R.layout.activity_fragment_container);
	}

	private void onStateChanged(SetupViewModel.State state) {
		if (state == AUTHOR_NAME) {
			setInputStateAlwaysVisible(this);
			showInitialFragment(AuthorNameFragment.newInstance());
		} else if (state == SET_PASSWORD) {
			setInputStateAlwaysVisible(this);
			showPasswordFragment();
		} else if (state == DOZE) {
			setInputStateHidden(this);
			showDozeFragment();
		} else if (state == CREATED || state == FAILED) {
			// TODO: Show an error if failed
			showApp();
		}
	}

	private void showPasswordFragment() {
		showNextFragment(SetPasswordFragment.newInstance());
	}

	@TargetApi(23)
	private void showDozeFragment() {
		showNextFragment(DozeFragment.newInstance());
	}

	private void showApp() {
		Intent i = new Intent(this, ENTRY_ACTIVITY);
		i.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME |
				FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
		supportFinishAfterTransition();
		overridePendingTransition(R.anim.screen_new_in, R.anim.screen_old_out);
	}

	@Override
	@Deprecated
	public void runOnDbThread(Runnable runnable) {
		throw new RuntimeException("Don't use this deprecated method here.");
	}

}
