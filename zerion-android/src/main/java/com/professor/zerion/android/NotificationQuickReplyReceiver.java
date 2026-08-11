package com.professor.zerion.android;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;

import javax.inject.Inject;
import javax.inject.Provider;

import androidx.core.app.RemoteInput;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;

public class NotificationQuickReplyReceiver extends BroadcastReceiver {

	static final String KEY_REPLY_TEXT = "key_reply_text";

	@Inject
	Provider<TransactionManager> dbProvider;
	@Inject
	Provider<MessagingManager> messagingManagerProvider;
	@Inject
	Provider<ConversationManager> conversationManagerProvider;
	@Inject
	Provider<PrivateMessageFactory> privateMessageFactoryProvider;

	@Override
	public void onReceive(Context ctx, Intent intent) {
		ZerionApplication app =
				(ZerionApplication) ctx.getApplicationContext();
		AndroidComponent component = app.getApplicationComponent();
		component.inject(this);

		Bundle remoteInputResults = RemoteInput.getResultsFromIntent(intent);
		if (remoteInputResults == null) return;
		CharSequence replyText =
				remoteInputResults.getCharSequence(KEY_REPLY_TEXT);
		if (replyText == null || replyText.length() == 0) return;

		int contactIdInt = intent.getIntExtra(CONTACT_ID, -1);
		if (contactIdInt == -1) return;

		String text = replyText.toString();
		ContactId recipientId = new ContactId(contactIdInt);

		PendingResult pendingResult = goAsync();
		new Thread(() -> {
			try {
				TransactionManager db = dbProvider.get();
				MessagingManager messagingManager =
						messagingManagerProvider.get();
				ConversationManager conversationManager =
						conversationManagerProvider.get();
				PrivateMessageFactory pmFactory =
						privateMessageFactoryProvider.get();

				db.transaction(false, txn -> {
					GroupId groupId = messagingManager
							.getConversationId(txn, recipientId);
					long timestamp = conversationManager
							.getTimestampForOutgoingMessage(txn,
									recipientId);
					PrivateMessage pm = pmFactory
							.createLegacyPrivateMessage(groupId,
									timestamp, text);
					messagingManager.addLocalMessage(txn, pm);
				});

				NotificationManager nm = (NotificationManager)
						ctx.getSystemService(Context.NOTIFICATION_SERVICE);
				if (nm != null) {
					nm.cancel(
							AndroidNotificationManagerImpl
									.CONTACT_NOTIFICATION_ID_BASE
									+ contactIdInt);
				}
			} catch (DbException | FormatException e) {
			} finally {
				pendingResult.finish();
			}
		}, "NotificationQuickReply").start();
	}
}
