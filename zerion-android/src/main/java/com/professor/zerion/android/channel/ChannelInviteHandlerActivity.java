package com.professor.zerion.android.channel;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelInviteHandlerActivity extends ZerionActivity {

	@Inject
	ChannelManager channelManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	AccountManager accountManager;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		if (accountManager.getDatabaseKey() == null) {
			finish();
			return;
		}
		Uri data = getIntent() == null ? null : getIntent().getData();
		if (data == null) {
			finish();
			return;
		}
		ChannelInviteLink link =
				channelManager.parseInviteLink(data.toString());
		if (link == null) {
			Toast.makeText(this,
					R.string.channels_join_error_link,
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		if (link.requiresApproval()) {
			showApplyDialog(link);
		} else {
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.channels_join_title)
					.setMessage(data.toString())
					.setCancelable(false)
					.setPositiveButton(R.string.channels_join_action,
							(d, w) -> handleJoin(link))
					.setNegativeButton(android.R.string.cancel,
							(d, w) -> finish())
					.show();
		}
	}

	private void showApplyDialog(ChannelInviteLink link) {
		View view = LayoutInflater.from(this).inflate(
				R.layout.dialog_apply_to_join, null);
		TextInputEditText nameInput =
				view.findViewById(R.id.channelApplyNameInput);
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_apply_title)
				.setView(view)
				.setCancelable(false)
				.setPositiveButton(R.string.channels_apply_action,
						(d, w) -> handleApply(link, nameInput))
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> finish())
				.show();
	}

	private void handleApply(ChannelInviteLink link,
			TextInputEditText nameInput) {
		String name = nameInput.getText() == null
				? "" : nameInput.getText().toString().trim();
		if (name.isEmpty()) {
			Toast.makeText(this,
					R.string.channels_create_error_name,
					Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		byte[] cid = link.getChannelId();
		android.app.ProgressDialog progress = showInlineProgress();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				if (s == null) {
					channelManager.joinChannel(link);
				}
				channelManager.applyToJoin(cid, name);
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					openChannel(cid);
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					Toast.makeText(this,
							R.string.channels_apply_failed,
							Toast.LENGTH_LONG).show();
					finish();
				});
			}
		});
	}

	private android.app.ProgressDialog showInlineProgress() {
		android.app.ProgressDialog d = new android.app.ProgressDialog(this);
		d.setMessage(getString(R.string.channels_apply_progress));
		d.setCancelable(false);
		d.show();
		return d;
	}

	private void handleJoin(ChannelInviteLink link) {
		byte[] cid = link.getChannelId();
		android.app.ProgressDialog progress = showInlineProgress();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				if (s == null) {
					channelManager.joinChannel(link);
					ioExecutor.execute(() -> {
						try {
							channelManager.bootstrapChannel(cid);
						} catch (DbException ignored) {
						}
					});
				}
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					openChannel(cid);
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					Toast.makeText(this,
							R.string.channels_join_error_link,
							Toast.LENGTH_LONG).show();
					finish();
				});
			}
		});
	}

	private void openChannel(byte[] channelId) {
		startActivity(ChannelFeedActivity.intent(this, channelId));
		finish();
	}
}
