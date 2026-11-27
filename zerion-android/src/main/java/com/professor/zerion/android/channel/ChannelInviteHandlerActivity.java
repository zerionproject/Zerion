package com.professor.zerion.android.channel;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.util.DisplayNames;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelRejoinResult;
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
		com.professor.zerion.android.util.SafeIntents
				.dropUnreadableExtras(getIntent());
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
		byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			boolean held = isHeld(cid);
			String name = held ? nameOf(cid) : "";
			runOnUiThreadUnlessDestroyed(() -> {
				if (!held && link.requiresApproval()) {
					showApplyDialog(link, 0, false);
				} else {
					showJoinDialog(link, held, name);
				}
			});
		});
	}

	static String joinQuestion(android.content.Context ctx, boolean held,
			String name) {
		String join = ctx.getString(R.string.channels_join_confirm_question);
		if (!held) {
			return ctx.getString(R.string.channels_link_name_unknown) + " "
					+ join;
		}
		String clean = DisplayNames.clean(name).trim();
		if (clean.isEmpty()) return join;
		return ctx.getString(R.string.channels_join_known_question,
				DisplayNames.isolate(clean));
	}

	private void showJoinDialog(ChannelInviteLink link, boolean held,
			String name) {
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_join_title)
				.setMessage(joinQuestion(this, held, name))
				.setCancelable(true)
				.setOnCancelListener(d -> finish())
				.setPositiveButton(R.string.channels_join_action,
						(d, w) -> {
							if (held) checkHeld(link);
							else handleJoin(link);
						})
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> finish())
				.show();
	}

	private boolean isHeld(byte[] channelId) {
		try {
			return channelManager.getChannel(channelId) != null;
		} catch (DbException e) {
			return false;
		}
	}

	private void checkHeld(ChannelInviteLink link) {
		byte[] cid = link.getChannelId();
		android.app.ProgressDialog progress = showInlineProgress();
		ioExecutor.execute(() -> {
			String name = nameOf(cid);
			ChannelRejoinResult result;
			try {
				result = channelManager.rejoinChannel(link);
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					finish();
				});
				return;
			}
			runOnUiThreadUnlessDestroyed(() -> {
				progress.dismiss();
				if (result == ChannelRejoinResult.DELETED) {
					Toast.makeText(this, deletedMessage(this, name),
							Toast.LENGTH_LONG).show();
					finish();
				} else if (applyMessage(result) != 0) {
					showApplyDialog(link, applyMessage(result));
				} else if (result == ChannelRejoinResult.CHECK_LINK) {
					showTryLinkDialog(link);
				} else {
					int message = rejoinMessage(result);
					if (message != 0) {
						Toast.makeText(this, message, Toast.LENGTH_LONG)
								.show();
					}
					openChannel(cid);
				}
			});
		});
	}

	private void showTryLinkDialog(ChannelInviteLink link) {
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_rejoin_check_title)
				.setMessage(R.string.channels_rejoin_check_message)
				.setCancelable(false)
				.setPositiveButton(R.string.channels_rejoin_check_action,
						(d, w) -> tryLink(link))
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> openChannel(link.getChannelId()))
				.show();
	}

	private void tryLink(ChannelInviteLink link) {
		byte[] cid = link.getChannelId();
		android.app.ProgressDialog progress = showInlineProgress();
		ioExecutor.execute(() -> {
			String name = nameOf(cid);
			ChannelRejoinResult result;
			try {
				result = channelManager.rejoinChannelThroughLink(link);
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					Toast.makeText(this,
							R.string.channels_rejoin_unreachable,
							Toast.LENGTH_LONG).show();
					openChannel(cid);
				});
				return;
			}
			if (result == ChannelRejoinResult.UPDATED) {
				ioExecutor.execute(() -> {
					try {
						channelManager.refreshChannel(cid);
					} catch (DbException ignored) {
					}
				});
			} else if (result == ChannelRejoinResult.TRYING) {
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
			}
			runOnUiThreadUnlessDestroyed(() -> {
				progress.dismiss();
				if (result == ChannelRejoinResult.DELETED) {
					Toast.makeText(this, deletedMessage(this, name),
							Toast.LENGTH_LONG).show();
					finish();
					return;
				}
				if (result == ChannelRejoinResult.UPDATED) {
					Toast.makeText(this,
							getString(R.string.channels_rejoin_updated,
									DisplayNames.isolate(name)),
							Toast.LENGTH_LONG).show();
				} else if (rejoinMessage(result) != 0) {
					Toast.makeText(this, rejoinMessage(result),
							Toast.LENGTH_LONG).show();
				}
				openChannel(cid);
			});
		});
	}

	static String deletedMessage(android.content.Context ctx, String name) {
		String clean = DisplayNames.clean(name).trim();
		return clean.isEmpty()
				? ctx.getString(R.string.channels_owner_deleted_notice_unnamed)
				: ctx.getString(R.string.channels_owner_deleted_notice,
						DisplayNames.isolate(clean));
	}

	static int rejoinMessage(ChannelRejoinResult result) {
		switch (result) {
			case NOT_RESTORED:
				return R.string.channels_rejoin_not_restored;
			case TRY_LATER:
				return R.string.channels_rejoin_try_later;
			case TRYING:
				return R.string.channels_rejoin_trying;
			case TOO_MANY_LINKS:
				return R.string.channels_rejoin_too_many_links;
			default:
				return 0;
		}
	}

	private String nameOf(byte[] channelId) {
		try {
			ChannelState s = channelManager.getChannel(channelId);
			return s == null ? "" : DisplayNames.clean(s.getName()).trim();
		} catch (DbException e) {
			return "";
		}
	}

	static int applyMessage(ChannelRejoinResult result) {
		if (result == ChannelRejoinResult.APPLY_AGAIN) {
			return R.string.channels_rejoin_request_again;
		}
		if (result == ChannelRejoinResult.APPLY_AGAIN_UNREACHABLE) {
			return R.string.channels_rejoin_request_again_unreachable;
		}
		return 0;
	}

	private void showApplyDialog(ChannelInviteLink link, int message) {
		showApplyDialog(link, message, true);
	}

	private void showApplyDialog(ChannelInviteLink link, int message,
			boolean held) {
		View view = LayoutInflater.from(this).inflate(
				R.layout.dialog_apply_to_join, null);
		android.widget.TextView about =
				view.findViewById(R.id.channelApplyMessage);
		if (message != 0) {
			about.setText(message);
		} else if (!held) {
			about.setText(getString(R.string.channels_link_name_unknown)
					+ "\n\n"
					+ getString(R.string.channels_request_access_message));
		}
		TextInputEditText nameInput =
				view.findViewById(R.id.channelApplyNameInput);
		nameInput.setFilters(new android.text.InputFilter[] {
				new com.professor.zerion.android.util.Utf8ByteLengthFilter(
						org.zerionproject.app.api.channel.ChannelConstants
								.MAX_DISPLAY_NAME_BYTES)});
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_request_access_title)
				.setView(view)
				.setCancelable(true)
				.setOnCancelListener(d -> finish())
				.setPositiveButton(R.string.channels_request_access_action,
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
					R.string.channels_request_access_name_missing,
					Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		byte[] cid = link.getChannelId();
		android.app.ProgressDialog progress = showInlineProgress();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				try {
					if (s == null) {
						channelManager.joinChannel(link);
						channelManager.applyToJoin(cid, name);
					} else {
						channelManager.applyToJoin(link, name);
					}
				} catch (DbException ex) {
					runOnUiThreadUnlessDestroyed(() -> {
						progress.dismiss();
						Toast.makeText(this,
								ex instanceof org.zerionproject.app.api.channel
										.ChannelApplicationUnconfirmedException
										? R.string.channels_request_access_unconfirmed
										: R.string.channels_request_access_failed,
								Toast.LENGTH_LONG).show();
						openChannel(cid);
					});
					return;
				}
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
							R.string.channels_request_access_failed,
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
		com.professor.zerion.android.security.SecureDialogs.applyHostPolicy(d);
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
