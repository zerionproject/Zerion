package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.login.AccountPasswordCheck;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.settings.AccountPasswordGate;
import com.professor.zerion.android.vault.ui.IncognitoInputHelper;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.app.api.channel.ChannelDeleteNotSignedException;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.db.DbException;

import java.util.concurrent.Executor;

import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelDeleteFlow {

	interface Listener {

		void onDeleted();

		void onNotDeleted(CharSequence reason);
	}

	enum Result { DELETED, PENDING, NOT_SIGNED, FAILED, REMOVED_HERE }

	enum Kind { WRONG, LOCKED, ERASE, DONE }

	static final class Outcome {

		final Kind kind;
		final long lockedMs;
		@Nullable
		final Result result;

		private Outcome(Kind kind, long lockedMs, @Nullable Result result) {
			this.kind = kind;
			this.lockedMs = lockedMs;
			this.result = result;
		}
	}

	private final FragmentActivity activity;
	private final ChannelManager channelManager;
	private final AccountManager accountManager;
	private final Executor ioExecutor;
	private final byte[] channelId;
	private final Listener listener;
	private final ChannelDeleteWork work;
	@Nullable
	private PasswordStep step;

	ChannelDeleteFlow(FragmentActivity activity, ChannelManager channelManager,
			AccountManager accountManager, Executor ioExecutor,
			byte[] channelId, Listener listener) {
		this.activity = activity;
		this.channelManager = channelManager;
		this.accountManager = accountManager;
		this.ioExecutor = ioExecutor;
		this.channelId = channelId;
		this.listener = listener;
		work = new ViewModelProvider(activity).get(ChannelDeleteWork.class);
		work.attach(activity);
		work.outcome.observe(activity, this::onOutcome);
		if (work.running) {
			step = new PasswordStep();
			step.show();
			step.setBusy(true);
		} else if (work.offerHere) {
			showHereOffer();
		}
	}

	static boolean typedTheWord(@Nullable CharSequence typed, String word) {
		return typed != null
				&& typed.toString().trim().equalsIgnoreCase(word);
	}

	static Result deleteForEveryone(ChannelManager manager,
			byte[] channelId) {
		try {
			manager.deleteChannelForEveryone(channelId);
		} catch (ChannelDeleteNotSignedException e) {
			return Result.NOT_SIGNED;
		} catch (DbException | RuntimeException e) {
		}
		return afterDelete(manager, channelId);
	}

	private static Result afterDelete(ChannelManager manager,
			byte[] channelId) {
		try {
			if (!manager.hasDeletionNotice(channelId)) return Result.FAILED;
		} catch (DbException | RuntimeException e) {
			return Result.FAILED;
		}
		try {
			return manager.getChannel(channelId) == null
					? Result.DELETED : Result.PENDING;
		} catch (DbException | RuntimeException e) {
			return Result.PENDING;
		}
	}

	static Result removeHere(ChannelManager manager, byte[] channelId) {
		boolean threw = false;
		try {
			manager.deleteChannel(channelId);
		} catch (DbException | RuntimeException e) {
			threw = true;
		}
		boolean notice;
		try {
			notice = manager.hasDeletionNotice(channelId);
		} catch (DbException | RuntimeException e) {
			notice = false;
		}
		boolean gone;
		try {
			gone = manager.getChannel(channelId) == null;
		} catch (DbException | RuntimeException e) {
			gone = false;
		}
		if (notice) return gone ? Result.DELETED : Result.PENDING;
		return !threw && gone ? Result.REMOVED_HERE : Result.FAILED;
	}

	private static int doneMessage(Result result) {
		if (result == Result.REMOVED_HERE) {
			return R.string.channels_delete_here_done;
		}
		return result == Result.PENDING
				? R.string.channels_delete_done_pending
				: R.string.channels_delete_done;
	}

	private static boolean done(@Nullable Result result) {
		return result == Result.DELETED || result == Result.PENDING
				|| result == Result.REMOVED_HERE;
	}

	void start(String name) {
		if (work.running) return;
		work.name = name;
		work.here = false;
		work.offerHere = false;
		showWordStep(false);
	}

	private void showWordStep(boolean here) {
		View view = LayoutInflater.from(activity).inflate(
				R.layout.dialog_channel_delete, null);
		TextInputLayout layout = view.findViewById(
				R.id.channelDeleteWordLayout);
		EditText input = view.findViewById(R.id.channelDeleteWordInput);
		String word = activity.getString(R.string.delete_confirm_word);
		layout.setHint(activity.getString(R.string.delete_confirm_hint, word));
		AlertDialog dialog = new SecureAlertDialogBuilder(activity)
				.setTitle(here ? R.string.channels_delete_here_title
						: R.string.channels_delete_everyone_title)
				.setMessage(here ? R.string.channels_delete_here_message
						: R.string.channels_delete_everyone_message)
				.setView(view)
				.setPositiveButton(R.string.continue_button, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		dialog.setOnShowListener(d -> {
			Button go = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			go.setTextColor(dangerColors());
			go.setEnabled(false);
			go.setOnClickListener(v -> {
				if (!typedTheWord(input.getText(), word)) return;
				dialog.dismiss();
				step = new PasswordStep();
				step.show();
			});
			input.addTextChangedListener(new AfterChange() {
				@Override
				public void afterTextChanged(Editable s) {
					go.setEnabled(typedTheWord(s, word));
				}
			});
		});
		dialog.show();
	}

	private ColorStateList dangerColors() {
		return new ColorStateList(new int[][] {
				new int[] {-android.R.attr.state_enabled}, new int[0]},
				new int[] {resolveColorAttribute(activity,
						R.attr.zerionColorOnSurfaceMuted),
						resolveColorAttribute(activity,
								R.attr.zerionColorDangerText)});
	}

	private void showHereOffer() {
		AlertDialog dialog = new SecureAlertDialogBuilder(activity)
				.setMessage(activity.getString(
						R.string.channels_delete_not_signed) + "\n\n"
						+ activity.getString(R.string.channels_delete_here_offer))
				.setPositiveButton(R.string.channels_delete_here_choice,
						(d, w) -> {
							work.offerHere = false;
							work.here = true;
							showWordStep(true);
						})
				.setNegativeButton(R.string.channels_delete_keep,
						(d, w) -> work.offerHere = false)
				.setOnCancelListener(d -> work.offerHere = false)
				.create();
		dialog.setOnShowListener(d -> dialog.getButton(
				AlertDialog.BUTTON_POSITIVE).setTextColor(dangerColors()));
		dialog.show();
	}

	private void submit(char[] chars) {
		work.running = true;
		AccountPasswordCheck.DuressCheck duress =
				AccountPasswordCheck.duressPasswordOf(activity);
		ChannelDeleteWork w = work;
		ChannelManager manager = channelManager;
		AccountManager accounts = accountManager;
		Executor io = ioExecutor;
		byte[] id = channelId;
		boolean here = work.here;
		ioExecutor.execute(() -> check(w, manager, accounts, io, id, duress,
				chars, here));
	}

	private static void check(ChannelDeleteWork work, ChannelManager manager,
			AccountManager accounts, Executor io, byte[] channelId,
			AccountPasswordCheck.DuressCheck duress, char[] chars,
			boolean here) {
		AccountPasswordGate.Result check =
				AccountPasswordGate.verify(accounts, duress, chars);
		switch (check.outcome) {
			case ERASE:
				Context context = work.context();
				if (context != null) {
					AccountPasswordGate.eraseFromSession(context, accounts,
							io);
				}
				deliver(work, new Outcome(Kind.ERASE, 0L, null));
				return;
			case LOCKED:
				deliver(work, new Outcome(Kind.LOCKED, check.lockedMs, null));
				return;
			case GRANTED:
				break;
			default:
				deliver(work, new Outcome(Kind.WRONG, 0L, null));
				return;
		}
		Result result = here ? removeHere(manager, channelId)
				: deleteForEveryone(manager, channelId);
		deliver(work, new Outcome(Kind.DONE, 0L, result));
	}

	private static void deliver(ChannelDeleteWork work, Outcome outcome) {
		Context app = work.appContext();
		if (work.isCleared() && done(outcome.result) && app != null) {
			int message = doneMessage(outcome.result);
			new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
					message, Toast.LENGTH_LONG).show());
			return;
		}
		work.outcome.postValue(outcome);
	}

	private void onOutcome(@Nullable Outcome outcome) {
		if (outcome == null) return;
		work.outcome.setValue(null);
		work.running = false;
		if (outcome.kind == Kind.WRONG || outcome.kind == Kind.LOCKED) {
			if (step == null || !step.sheet.isShowing()) {
				step = new PasswordStep();
				step.show();
			}
			step.refused(outcome);
			return;
		}
		if (step != null) {
			step.busy = false;
			step.sheet.dismiss();
			step = null;
		}
		if (outcome.kind == Kind.ERASE || outcome.result == null) return;
		if (done(outcome.result)) {
			Toast.makeText(activity, doneMessage(outcome.result),
					Toast.LENGTH_LONG).show();
			listener.onDeleted();
		} else if (outcome.result == Result.NOT_SIGNED) {
			work.offerHere = true;
			showHereOffer();
		} else {
			listener.onNotDeleted(activity.getText(work.here
					? R.string.channels_delete_here_failed
					: R.string.channels_delete_failed));
		}
	}

	private final class PasswordStep {

		private final View view = LayoutInflater.from(activity).inflate(
				R.layout.sheet_channel_delete_auth, null);
		private final TextInputLayout layout = view.findViewById(
				R.id.channelDeleteAuthPasswordLayout);
		private final EditText password = view.findViewById(
				R.id.channelDeleteAuthPassword);
		private final Button confirm = view.findViewById(
				R.id.channelDeleteAuthConfirm);
		private final Button cancel = view.findViewById(
				R.id.channelDeleteAuthCancel);
		private final View progress = view.findViewById(
				R.id.channelDeleteAuthProgress);
		final BottomSheetDialog sheet =
				ChannelSheets.create(activity, view, true);
		boolean busy = false;
		private boolean locked = false;

		void show() {
			((TextView) view.findViewById(R.id.channelDeleteAuthMessage))
					.setText(activity.getString(work.here
							? R.string.channels_delete_here_auth_message
							: R.string.channels_delete_auth_message, work.name));
			if (work.here) confirm.setText(R.string.channels_delete_here_action);
			IncognitoInputHelper.configurePasswordField(password);
			confirm.setEnabled(false);
			password.addTextChangedListener(new AfterChange() {
				@Override
				public void afterTextChanged(Editable s) {
					if (s.length() > 0 && !locked) layout.setError(null);
					updateButtons();
				}
			});
			password.setOnEditorActionListener((v, actionId, event) -> {
				if (actionId != EditorInfo.IME_ACTION_DONE) return false;
				if (confirm.isEnabled()) confirm.performClick();
				return true;
			});
			confirm.setOnClickListener(v -> submitTyped());
			cancel.setOnClickListener(v -> {
				if (!busy) sheet.dismiss();
			});
			sheet.setOnDismissListener(d -> {
				Editable typed = password.getText();
				if (typed != null) typed.clear();
			});
			sheet.show();
		}

		void setBusy(boolean busy) {
			this.busy = busy;
			updateButtons();
		}

		private void updateButtons() {
			Editable typed = password.getText();
			confirm.setEnabled(!busy && !locked && typed != null
					&& typed.length() > 0);
			cancel.setEnabled(!busy);
			password.setEnabled(!busy && !locked);
			progress.setVisibility(busy ? View.VISIBLE : View.GONE);
			sheet.setCancelable(!busy);
		}

		private void submitTyped() {
			Editable typed = password.getText();
			if (busy || locked || typed == null || typed.length() == 0) {
				return;
			}
			char[] chars = new char[typed.length()];
			typed.getChars(0, typed.length(), chars, 0);
			typed.clear();
			setBusy(true);
			submit(chars);
		}

		void refused(Outcome outcome) {
			busy = false;
			if (outcome.kind == Kind.LOCKED) {
				locked = true;
				int minutes = Math.max(1,
						(int) Math.ceil(outcome.lockedMs / 60000.0));
				layout.setError(activity.getString(
						R.string.hardened_block_password_locked, minutes));
			} else {
				layout.setError(activity.getString(
						R.string.channels_delete_auth_wrong));
			}
			updateButtons();
			if (!locked) password.requestFocus();
		}
	}

	private abstract static class AfterChange implements TextWatcher {

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count,
				int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before,
				int count) {
		}
	}
}
