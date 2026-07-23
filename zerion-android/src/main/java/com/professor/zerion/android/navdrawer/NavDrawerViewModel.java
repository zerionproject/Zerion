package com.professor.zerion.android.navdrawer;

import android.app.Application;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelPostReceivedEvent;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.event.ConversationMessageTrackedEvent;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.settings.OwnIdentityInfo;
import com.professor.zerion.android.viewmodel.DbViewModel;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.briarproject.android.dontkillmelib.DozeUtils.needsDozeWhitelisting;
import static com.professor.zerion.android.TestingConstants.EXPIRY_DATE;
import static com.professor.zerion.android.controller.ZerionControllerImpl.DOZE_ASK_AGAIN;
import static com.professor.zerion.android.settings.SettingsFragment.SETTINGS_NAMESPACE;

@NotNullByDefault
public class NavDrawerViewModel extends DbViewModel
		implements EventListener {

	private static final String EXPIRY_DATE_WARNING = "expiryDateWarning";
	private static final String SHOW_TRANSPORTS_ONBOARDING =
			"showTransportsOnboarding";

	private final SettingsManager settingsManager;
	private final IdentityManager identityManager;
	private final AuthorManager authorManager;
	private final ContactManager contactManager;
	private final ConversationManager conversationManager;
	private final GroupTrManager groupTrManager;
	private final ChannelManager channelManager;
	private final EventBus eventBus;

	private final MutableLiveData<Integer> unreadContacts =
			new MutableLiveData<>();
	private final MutableLiveData<Integer> unreadGroups =
			new MutableLiveData<>();
	private final MutableLiveData<Integer> unreadChannels =
			new MutableLiveData<>();

	private final MutableLiveData<Boolean> showExpiryWarning =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> shouldAskForDozeWhitelisting =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> showTransportsOnboarding =
			new MutableLiveData<>();
	private final MutableLiveData<OwnIdentityInfo> ownIdentityInfo =
			new MutableLiveData<>();

	@Inject
	NavDrawerViewModel(Application app,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			SettingsManager settingsManager,
			IdentityManager identityManager,
			AuthorManager authorManager,
			ContactManager contactManager,
			ConversationManager conversationManager,
			GroupTrManager groupTrManager,
			ChannelManager channelManager,
			EventBus eventBus) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.settingsManager = settingsManager;
		this.identityManager = identityManager;
		this.authorManager = authorManager;
		this.contactManager = contactManager;
		this.conversationManager = conversationManager;
		this.groupTrManager = groupTrManager;
		this.channelManager = channelManager;
		this.eventBus = eventBus;
		eventBus.addListener(this);
		loadOwnIdentityInfo();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ConversationMessageTrackedEvent
				|| e instanceof ConversationMessagesDeletedEvent
				|| e instanceof ChannelPostReceivedEvent
				|| e instanceof ChannelCommentReceivedEvent
				|| e instanceof ContactConnectedEvent) {
			checkUnreadCounts();
		}
	}

	LiveData<Boolean> showExpiryWarning() {
		return showExpiryWarning;
	}

	@UiThread
	void checkExpiryWarning() {
		runOnDbThread(() -> {
			try {
				Settings settings =
						settingsManager.getSettings(SETTINGS_NAMESPACE);
				int warningInt = settings.getInt(EXPIRY_DATE_WARNING, 0);

				if (warningInt == 0) {
					showExpiryWarning.postValue(true);
				} else {
					long warningLong = warningInt * 1000L;
					long now = System.currentTimeMillis();
					long daysSinceLastWarning =
							(now - warningLong) / DAYS.toMillis(1);
					long daysBeforeExpiry =
							(EXPIRY_DATE - now) / DAYS.toMillis(1);

					if (daysSinceLastWarning >= 30) {
						showExpiryWarning.postValue(true);
					} else if (daysBeforeExpiry <= 3 &&
							daysSinceLastWarning > 0) {
						showExpiryWarning.postValue(true);
					} else {
						showExpiryWarning.postValue(false);
					}
				}
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@UiThread
	void expiryWarningDismissed() {
		showExpiryWarning.setValue(false);
		runOnDbThread(() -> {
			try {
				Settings settings = new Settings();
				int date = (int) (System.currentTimeMillis() / 1000L);
				settings.putInt(EXPIRY_DATE_WARNING, date);
				settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<Boolean> shouldAskForDozeWhitelisting() {
		return shouldAskForDozeWhitelisting;
	}

	@UiThread
	void checkDozeWhitelisting() {
		ZerionApplication app = getApplication();
		if (app.isInstrumentationTest() ||
				!needsDozeWhitelisting(getApplication())) {
			shouldAskForDozeWhitelisting.setValue(false);
			return;
		}
		runOnDbThread(() -> {
			try {
				Settings settings =
						settingsManager.getSettings(SETTINGS_NAMESPACE);
				boolean ask = settings.getBoolean(DOZE_ASK_AGAIN, true);
				shouldAskForDozeWhitelisting.postValue(ask);
			} catch (DbException e) {
				shouldAskForDozeWhitelisting.postValue(true);
			}
		});
	}

	@UiThread
	LiveData<Boolean> showTransportsOnboarding() {
		return showTransportsOnboarding;
	}

	@UiThread
	void checkTransportsOnboarding() {
		if (showTransportsOnboarding.getValue() != null) return;
		runOnDbThread(() -> {
			try {
				Settings settings =
						settingsManager.getSettings(SETTINGS_NAMESPACE);
				boolean show =
						settings.getBoolean(SHOW_TRANSPORTS_ONBOARDING, true);
				showTransportsOnboarding.postValue(show);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@UiThread
	void transportsOnboardingShown() {
		showTransportsOnboarding.setValue(false);
		runOnDbThread(() -> {
			try {
				Settings settings = new Settings();
				settings.putBoolean(SHOW_TRANSPORTS_ONBOARDING, false);
				settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	private void loadOwnIdentityInfo() {
		runOnDbThread(() -> {
			try {
				LocalAuthor localAuthor = identityManager.getLocalAuthor();
				AuthorInfo authorInfo = authorManager.getMyAuthorInfo();
				ownIdentityInfo.postValue(
						new OwnIdentityInfo(localAuthor, authorInfo));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<OwnIdentityInfo> getOwnIdentityInfo() {
		return ownIdentityInfo;
	}

	LiveData<Integer> getUnreadContacts() {
		return unreadContacts;
	}

	LiveData<Integer> getUnreadGroups() {
		return unreadGroups;
	}

	LiveData<Integer> getUnreadChannels() {
		return unreadChannels;
	}

	/**
	 * Recomputes the total unread count for each tab (summed across all
	 * contacts, groups and channels) on the database thread and posts each to
	 * its LiveData. Cheap enough to call on resume and on tab changes.
	 */
	@UiThread
	void checkUnreadCounts() {
		runOnDbThread(() -> {
			try {
				int contacts = 0;
				for (Contact c : contactManager.getContacts()) {
					contacts += conversationManager.getGroupCount(c.getId())
							.getUnreadCount();
				}
				int groups = 0;
				for (GroupTrState g : groupTrManager.getGroups()) {
					groups += groupTrManager.getUnreadCount(g.getGroupId());
				}
				int channels = 0;
				for (ChannelState ch : channelManager.getChannels()) {
					channels += channelManager.getUnreadCount(ch.getChannelId());
				}
				unreadContacts.postValue(contacts);
				unreadGroups.postValue(groups);
				unreadChannels.postValue(channels);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}
}
