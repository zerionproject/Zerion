package com.professor.zerion.android.introduction;

import android.app.Application;
import android.widget.Toast;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.R;
import com.professor.zerion.android.contact.ContactItem;
import com.professor.zerion.android.contact.ContactsViewModel;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;

@NotNullByDefault
class IntroductionViewModel extends ContactsViewModel {


	private final ContactManager contactManager;
	private final AuthorManager authorManager;
	private final IntroductionManager introductionManager;

	@Inject
	IntroductionViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ContactManager contactManager,
			AuthorManager authorManager,
			ConversationManager conversationManager,
			ConnectionRegistry connectionRegistry, EventBus eventBus,
			IntroductionManager introductionManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				contactManager, authorManager, conversationManager,
				connectionRegistry, eventBus);
		this.contactManager = contactManager;
		this.authorManager = authorManager;
		this.introductionManager = introductionManager;
	}

	@Nullable
	private ContactId firstContactId;
	@Nullable
	private ContactId secondContactId;

	private final MutableLiveEvent<Boolean> secondContactSelected =
			new MutableLiveEvent<>();

	private final MutableLiveData<IntroductionInfo> introductionInfo =
			new MutableLiveData<>();

	void setFirstContactId(ContactId contactId) {
		this.firstContactId = contactId;
		loadContacts();
	}

	@Nullable
	ContactId getSecondContactId() {
		return secondContactId;
	}

	void setSecondContactId(ContactId contactId) {
		secondContactId = contactId;
		introductionInfo.setValue(null);
		loadIntroductionInfo();
	}

	void triggerContactSelected() {
		secondContactSelected.setEvent(true);
	}

	LiveEvent<Boolean> getSecondContactSelected() {
		return secondContactSelected;
	}

	LiveData<IntroductionInfo> getIntroductionInfo() {
		return introductionInfo;
	}

	@Override
	protected boolean displayContact(ContactId contactId) {
		return !requireNonNull(firstContactId).equals(contactId);
	}

	private void loadIntroductionInfo() {
		final ContactId firstContactId = requireNonNull(this.firstContactId);
		final ContactId secondContactId = requireNonNull(this.secondContactId);
		runOnDbThread(() -> {
			try {
				Contact firstContact =
						contactManager.getContact(firstContactId);
				Contact secondContact =
						contactManager.getContact(secondContactId);
				AuthorInfo a1 = authorManager.getAuthorInfo(firstContact);
				AuthorInfo a2 = authorManager.getAuthorInfo(secondContact);
				boolean possible = introductionManager
						.canIntroduce(firstContact, secondContact);
				ContactItem c1 = new ContactItem(firstContact, a1);
				ContactItem c2 = new ContactItem(secondContact, a2);
				introductionInfo.postValue(
						new IntroductionInfo(c1, c2, possible));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void makeIntroduction(@Nullable String text) {
		final IntroductionInfo info =
				requireNonNull(introductionInfo.getValue());
		runOnDbThread(() -> {
			try {
				introductionManager.makeIntroduction(
						info.getContact1().getContact(),
						info.getContact2().getContact(), text);
			} catch (DbException e) {
				androidExecutor.runOnUiThread(() -> Toast.makeText(
						getApplication(), R.string.introduction_error,
						LENGTH_SHORT).show());
			}
		});
	}

}
