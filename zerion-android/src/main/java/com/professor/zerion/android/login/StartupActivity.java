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
	protected boolean forceScreenshotProtection() {
		return true;
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
			if (deleted) {
				onAccountDeleted();
			} else {
				android.widget.Toast.makeText(this,
						R.string.delete_account_failed,
						android.widget.Toast.LENGTH_LONG).show();
			}
		});
		viewModel.getState().observe(this, this::onStateChanged);
		viewModel.decideStartAsync(this::onStartDecided);
	}

	private void onStartDecided(StartupViewModel.StartupDecision decision) {
		if (isFinishing()) return;
		switch (decision) {
			case ERASED:
			case NO_ACCOUNT:
				onAccountDeleted();
				break;
			case DATA_WITHOUT_ACCOUNT:
				showDataWithoutAccount();
				break;
			default:
				break;
		}
	}

	private void showDataWithoutAccount() {
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this, R.style.ZerionDialogTheme)
				.setTitle(R.string.dialog_title_key_files_damaged)
				.setMessage(R.string.startup_data_without_account_message)
				.setCancelable(false)
				.setPositiveButton(R.string.startup_data_without_account_keep,
						(d, w) -> finishAndRemoveTask())
				.setNegativeButton(R.string.startup_data_without_account_erase,
						(d, w) -> confirmEraseEverything())
				.show();
	}

	private void confirmEraseEverything() {
		String required = getString(R.string.delete_confirm_word);
		com.google.android.material.textfield.TextInputLayout til =
				new com.google.android.material.textfield.TextInputLayout(this);
		com.google.android.material.textfield.TextInputEditText confirm =
				new com.google.android.material.textfield.TextInputEditText(
						til.getContext());
		confirm.setHint(getString(R.string.delete_confirm_hint, required));
		confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		til.addView(confirm);
		int pad = Math.round(24 * getResources().getDisplayMetrics().density);
		til.setPadding(pad, 0, pad, 0);
		androidx.appcompat.app.AlertDialog dialog =
				new com.professor.zerion.android.security
						.SecureAlertDialogBuilder(this, R.style.ZerionDialogTheme)
						.setTitle(R.string.startup_data_without_account_erase)
						.setMessage(R.string.startup_erase_everything_confirm)
						.setView(til)
						.setCancelable(false)
						.setPositiveButton(R.string.cancel,
								(d, w) -> showDataWithoutAccount())
						.setNegativeButton(R.string.delete,
								(d, w) -> viewModel.deleteAccount())
						.create();
		dialog.setOnShowListener(d -> {
			android.widget.Button delete = dialog.getButton(
					androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
			delete.setEnabled(false);
			confirm.addTextChangedListener(new android.text.TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence t, int st, int c,
						int a) {
				}

				@Override
				public void onTextChanged(CharSequence t, int st, int b,
						int c) {
				}

				@Override
				public void afterTextChanged(android.text.Editable e) {
					delete.setEnabled(e.toString().trim()
							.equalsIgnoreCase(required));
				}
			});
		});
		dialog.show();
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
