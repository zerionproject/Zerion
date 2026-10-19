package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.util.UiUtils;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.system.AndroidExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.util.Consumer;

import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;

@NotNullByDefault
public class TextForwarder {

	private final TransactionManager db;
	private final LifecycleManager lifecycleManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final ConversationManager conversationManager;
	private final AutoDeleteManager autoDeleteManager;
	private final PrivateMessageFactory privateMessageFactory;
	private final Executor dbExecutor;
	private final AndroidExecutor androidExecutor;

	@Inject
	public TextForwarder(TransactionManager db,
			LifecycleManager lifecycleManager, ContactManager contactManager,
			MessagingManager messagingManager,
			ConversationManager conversationManager,
			AutoDeleteManager autoDeleteManager,
			PrivateMessageFactory privateMessageFactory,
			@DatabaseExecutor Executor dbExecutor,
			AndroidExecutor androidExecutor) {
		this.db = db;
		this.lifecycleManager = lifecycleManager;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.conversationManager = conversationManager;
		this.autoDeleteManager = autoDeleteManager;
		this.privateMessageFactory = privateMessageFactory;
		this.dbExecutor = dbExecutor;
		this.androidExecutor = androidExecutor;
	}

	static String limit(String text) {
		return text.length() > MAX_PRIVATE_MESSAGE_TEXT_LENGTH
				? text.substring(0, MAX_PRIVATE_MESSAGE_TEXT_LENGTH) : text;
	}

	@UiThread
	public void pickAndForward(Activity activity, String text,
			@Nullable ContactId except) {
		String forwardText = limit(text);
		loadContacts(except, contacts -> {
			if (activity.isFinishing() || activity.isDestroyed()) return;
			if (contacts == null) {
				Toast.makeText(activity, R.string.forward_failed,
						Toast.LENGTH_SHORT).show();
				return;
			}
			if (contacts.isEmpty()) {
				Toast.makeText(activity, R.string.no_other_contacts,
						Toast.LENGTH_SHORT).show();
				return;
			}
			String[] names = new String[contacts.size()];
			for (int i = 0; i < contacts.size(); i++) {
				names[i] = UiUtils.getContactDisplayName(contacts.get(i));
			}
			new SecureAlertDialogBuilder(activity)
					.setTitle(R.string.forward_to)
					.setItems(names, (dialog, which) -> forward(
							contacts.get(which).getId(), forwardText,
							() -> Toast.makeText(activity,
									R.string.message_forwarded,
									Toast.LENGTH_SHORT).show(),
							() -> Toast.makeText(activity,
									R.string.forward_failed,
									Toast.LENGTH_SHORT).show()))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		});
	}

	void loadContacts(@Nullable ContactId except,
			Consumer<List<Contact>> callback) {
		dbExecutor.execute(() -> {
			List<Contact> found;
			try {
				lifecycleManager.waitForDatabase();
				found = new ArrayList<>();
				for (Contact c : contactManager.getContacts()) {
					if (!c.getId().equals(except)) found.add(c);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (DbException e) {
				found = null;
			}
			List<Contact> result = found;
			androidExecutor.runOnUiThread(() -> callback.accept(result));
		});
	}

	void forward(ContactId recipientId, String text, Runnable onSuccess,
			Runnable onFailure) {
		dbExecutor.execute(() -> {
			try {
				lifecycleManager.waitForDatabase();
				db.transaction(false, txn -> {
					GroupId groupId = messagingManager
							.getConversationId(txn, recipientId);
					long timestamp = conversationManager
							.getTimestampForOutgoingMessage(txn, recipientId);
					PrivateMessage pm = OutgoingTextMessages.create(txn,
							messagingManager, autoDeleteManager,
							privateMessageFactory, recipientId, groupId,
							timestamp, text);
					messagingManager.addLocalMessage(txn, pm);
				});
				androidExecutor.runOnUiThread(onSuccess);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (DbException | FormatException e) {
				androidExecutor.runOnUiThread(onFailure);
			}
		});
	}
}
