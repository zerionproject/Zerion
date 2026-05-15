package com.professor.zerion.android;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.briarproject.bramble.api.Multiset;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.event.ContactAddedEvent;
import org.briarproject.bramble.api.plugin.event.B4OwnRotationCompletedEvent;
import org.briarproject.bramble.api.plugin.event.B4PeerOnionAnnouncedEvent;
import org.briarproject.bramble.api.crypto.SecretKey;
import com.professor.zerion.android.conversation.voice.VoiceCallKeyHolder;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.lifecycle.ServiceException;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.StringUtils;
import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.login.SignInReminderReceiver;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.splash.SplashScreenActivity;
import com.professor.zerion.android.util.ZerionNotificationBuilder;
import com.professor.zerion.android.api.AndroidNotificationManager;
import org.briarproject.briar.api.conversation.ConversationResponse;
import org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.VoiceSignal;
import org.briarproject.briar.api.messaging.VoiceSignalFactory;
import org.briarproject.briar.api.messaging.VoiceSignalHeader;
import org.briarproject.briar.api.messaging.VoiceSignalType;
import org.briarproject.briar.api.messaging.event.GroupPostReceivedEvent;
import org.briarproject.briar.api.messaging.event.PrivateMessageReceivedEvent;
import org.briarproject.briar.api.messaging.event.VoiceSignalReceivedEvent;
import com.professor.zerion.android.grouptr.GroupTrConversationActivity;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.briar.api.privategroup.event.GroupMessageAddedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;

import static android.app.Notification.DEFAULT_LIGHTS;
import static android.app.Notification.DEFAULT_SOUND;
import static android.app.Notification.DEFAULT_VIBRATE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.PendingIntent.getActivity;
import static android.app.PendingIntent.getBroadcast;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.net.Uri.EMPTY;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.core.app.NotificationCompat.CATEGORY_MESSAGE;
import static androidx.core.app.NotificationCompat.CATEGORY_SERVICE;
import static androidx.core.app.NotificationCompat.CATEGORY_SOCIAL;
import static androidx.core.app.NotificationCompat.PRIORITY_HIGH;
import static androidx.core.app.NotificationCompat.PRIORITY_LOW;
import static androidx.core.app.NotificationCompat.PRIORITY_MIN;
import static androidx.core.app.NotificationCompat.VISIBILITY_SECRET;
import static androidx.core.content.ContextCompat.getColor;
import static org.briarproject.bramble.util.AndroidUtils.getImmutableFlags;
import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.CONTACT_ADDED_URI;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.CONTACT_URI;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.GROUP_URI;
import static com.professor.zerion.android.settings.SettingsFragment.SETTINGS_NAMESPACE;

@ThreadSafe
@MethodsNotNullByDefault
@ParametersNotNullByDefault
class AndroidNotificationManagerImpl implements AndroidNotificationManager,
		Service, EventListener {

	private static final long SOUND_DELAY = TimeUnit.SECONDS.toMillis(2);

	static final int PRIVATE_MESSAGE_NOTIFICATION_ID = 2;
	private static final int GROUP_MESSAGE_NOTIFICATION_ID = 3;
	private static final int CONTACT_ADDED_NOTIFICATION_ID = 4;
	private static final int SIGN_IN_NOTIFICATION_ID = 5;
	private static final int REMINDER_NOTIFICATION_ID = 6;
	static final int CONTACT_NOTIFICATION_ID_BASE = 1000;
	static final int GROUP_TR_NOTIFICATION_ID_BASE = 5000;

	private final SettingsManager settingsManager;
	private final AndroidExecutor androidExecutor;
	private final Clock clock;
	private final Context appContext;
	private final NotificationManager notificationManager;
	private final MessagingManager messagingManager;
	private final ContactManager contactManager;
	private final SharedPreferences uiPrefs;
	private final VoiceSignalFactory voiceSignalFactory;
	private final AtomicBoolean used = new AtomicBoolean(false);

	private final Multiset<ContactId> contactCounts = new Multiset<>();
	private final Set<Integer> activeContactNotificationIds =
			new HashSet<>();
	private final Multiset<GroupId> groupCounts = new Multiset<>();
	private final Multiset<String> groupTrCounts = new Multiset<>();
	private final Set<Integer> activeGroupTrNotificationIds = new HashSet<>();
	private int contactAddedTotal = 0;
	private int nextRequestId = 0;
	@Nullable
	private ContactId blockedContact = null;
	@Nullable
	private GroupId blockedGroup = null;
	@Nullable
	private String blockedGroupTr = null;
	private boolean blockSignInReminder = false;
	private boolean blockGroups = false;
	private long lastSound = 0;

	private volatile Settings settings = new Settings();

	@Inject
	AndroidNotificationManagerImpl(SettingsManager settingsManager,
			AndroidExecutor androidExecutor, Application app, Clock clock,
			MessagingManager messagingManager, ContactManager contactManager,
			VoiceSignalFactory voiceSignalFactory,
			@AppModule.UiPrefs SharedPreferences uiPrefs) {
		this.settingsManager = settingsManager;
		this.androidExecutor = androidExecutor;
		this.clock = clock;
		this.messagingManager = messagingManager;
		this.contactManager = contactManager;
		this.voiceSignalFactory = voiceSignalFactory;
		this.uiPrefs = uiPrefs;
		appContext = app.getApplicationContext();
		notificationManager = (NotificationManager)
				appContext.getSystemService(NOTIFICATION_SERVICE);
	}

	@Override
	public void startService() throws ServiceException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		try {
			settings = settingsManager.getSettings(SETTINGS_NAMESPACE);
		} catch (DbException e) {
			throw new ServiceException(e);
		}
		if (SDK_INT >= 26) {
			Callable<Void> task = () -> {
				createNotificationChannel(CONTACT_CHANNEL_ID,
						R.string.contact_list_button);
				createNotificationChannel(GROUP_CHANNEL_ID,
						R.string.groups_button);
				createRotationNotificationChannel();
				return null;
			};
			try {
				androidExecutor.runOnUiThread(task).get();
			} catch (InterruptedException | ExecutionException e) {
				throw new ServiceException(e);
			}
		}
	}

	@TargetApi(26)
	private void createNotificationChannel(String channelId,
			@StringRes int name) {
		NotificationChannel nc =
				new NotificationChannel(channelId, appContext.getString(name),
						IMPORTANCE_DEFAULT);
		nc.setLockscreenVisibility(VISIBILITY_SECRET);
		nc.enableVibration(true);
		nc.enableLights(true);
		nc.setLightColor(getColor(appContext, R.color.zerion_lime_400));
		notificationManager.createNotificationChannel(nc);
	}

	@TargetApi(26)
	private void createRotationNotificationChannel() {
		NotificationChannel nc = new NotificationChannel(ROTATION_CHANNEL_ID,
				appContext.getString(
						R.string.rotation_notification_channel_title),
				IMPORTANCE_DEFAULT);
		nc.setShowBadge(false);
		nc.enableVibration(true);
		nc.setLockscreenVisibility(
				android.app.Notification.VISIBILITY_PUBLIC);
		notificationManager.createNotificationChannel(nc);
	}

	private void showRotationNotification(@StringRes int bodyResId,
			String idPrefix) {
		NotificationCompat.Builder b =
				new NotificationCompat.Builder(appContext, ROTATION_CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_notifications)
						.setContentTitle(appContext.getString(R.string.app_name))
						.setContentText(appContext.getString(bodyResId))
						.setGroup("rotation")
						.setCategory(NotificationCompat.CATEGORY_STATUS)
						.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
						.setAutoCancel(true);
		int id = (int) (java.util.UUID.randomUUID().getMostSignificantBits()
				& 0x7fffffff);
		notificationManager.notify(idPrefix + id, 0, b.build());
	}

	@Override
	public void stopService() throws ServiceException {
		Future<Void> f = androidExecutor.runOnUiThread(() -> {
			clearContactNotification();
			clearGroupMessageNotification();
			clearAllGroupTrNotifications();
			clearContactAddedNotification();
			return null;
		});
		try {
			f.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new ServiceException(e);
		}
	}

	@UiThread
	private void clearContactNotification() {
		contactCounts.clear();
		for (int id : activeContactNotificationIds) {
			notificationManager.cancel(id);
		}
		activeContactNotificationIds.clear();
		notificationManager.cancel(PRIVATE_MESSAGE_NOTIFICATION_ID);
	}

	@UiThread
	private void clearGroupMessageNotification() {
		groupCounts.clear();
		notificationManager.cancel(GROUP_MESSAGE_NOTIFICATION_ID);
	}

	@Override
	public void clearContactAddedNotification() {
		contactAddedTotal = 0;
		notificationManager.cancel(CONTACT_ADDED_NOTIFICATION_ID);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (s.getNamespace().equals(SETTINGS_NAMESPACE))
				settings = s.getSettings();
		} else if (e instanceof ConversationMessageReceivedEvent) {
			ConversationMessageReceivedEvent<?> p =
					(ConversationMessageReceivedEvent<?>) e;
			if (p.getMessageHeader() instanceof ConversationResponse) {
				ConversationResponse r =
						(ConversationResponse) p.getMessageHeader();
				if (r.isAutoDecline()) return;
			}
			if (e instanceof PrivateMessageReceivedEvent) {
				PrivateMessageReceivedEvent pm = (PrivateMessageReceivedEvent) e;
				checkForIncomingCall(pm);
			}

			showContactNotification(p.getContactId());
		} else if (e instanceof GroupMessageAddedEvent) {
			GroupMessageAddedEvent g = (GroupMessageAddedEvent) e;
			if (!g.isLocal()) showGroupMessageNotification(g.getGroupId());
		} else if (e instanceof GroupPostReceivedEvent) {
			GroupPostReceivedEvent g = (GroupPostReceivedEvent) e;
			showGroupTrPostNotification(g.getGroupId());
		} else if (e instanceof ContactAddedEvent) {
			ContactAddedEvent c = (ContactAddedEvent) e;
			if (!c.isVerified()) showContactAddedNotification();
		} else if (e instanceof VoiceSignalReceivedEvent) {
			VoiceSignalReceivedEvent voiceEvent = (VoiceSignalReceivedEvent) e;
			VoiceSignalHeader header = voiceEvent.getSignalHeader();
			if (header.getSignalType() == VoiceSignalType.CALL_OFFER) {
				handleIncomingVoiceCall(voiceEvent.getContactId(), header);
			}
		} else if (e instanceof B4OwnRotationCompletedEvent) {
			showRotationNotification(R.string.rotation_notification_own_body,
					"rotation_own_");
		} else if (e instanceof B4PeerOnionAnnouncedEvent) {
			showRotationNotification(R.string.rotation_notification_peer_body,
					"rotation_peer_");
		}
	}

	@UiThread
	@Override
	public Notification getForegroundNotification() {
		return getForegroundNotification(false);
	}

	@UiThread
	private Notification getForegroundNotification(boolean locked) {
		int title = locked ? R.string.lock_is_locked :
				R.string.ongoing_notification_title;
		int text = locked ? R.string.lock_tap_to_unlock :
				R.string.ongoing_notification_text;
		int icon = R.drawable.logo;
		NotificationCompat.Builder b =
				new NotificationCompat.Builder(appContext, ONGOING_CHANNEL_ID);
		b.setSmallIcon(icon);
		b.setColor(getColor(appContext, R.color.zerion_primary));
		b.setContentTitle(appContext.getText(title));
		b.setContentText(appContext.getText(text));
		b.setWhen(0);
		b.setOngoing(true);
		Intent i = new Intent(appContext, SplashScreenActivity.class);
		b.setContentIntent(getActivity(appContext, 0, i, getImmutableFlags(0)));
		b.setCategory(CATEGORY_SERVICE);
		b.setVisibility(VISIBILITY_SECRET);
		b.setPriority(PRIORITY_MIN);
		return b.build();
	}

	@UiThread
	@Override
	public void updateForegroundNotification(boolean locked) {
		Notification n = getForegroundNotification(locked);
		notificationManager.notify(ONGOING_NOTIFICATION_ID, n);
	}

	@Override
	public void showPrivateMessageNotification(ContactId c) {
		androidExecutor.runOnUiThread(() -> showContactNotification(c));
	}

	@UiThread
	private void showContactNotification(ContactId c) {
		if (c.equals(blockedContact)) return;
		contactCounts.add(c);
		if (!settings.getBoolean(PREF_NOTIFY_PRIVATE, true)) return;
		postContactNotification(c, true);
	}

	@Override
	public void clearContactNotification(ContactId c) {
		androidExecutor.runOnUiThread(() -> {
			contactCounts.removeAll(c);
			int notifId = CONTACT_NOTIFICATION_ID_BASE + c.getInt();
			notificationManager.cancel(notifId);
			activeContactNotificationIds.remove(notifId);
		});
	}

	@UiThread
	private void postContactNotification(ContactId c, boolean mayAlert) {
		int count = contactCounts.getCount(c);
		if (count == 0) return;
		int notifId = CONTACT_NOTIFICATION_ID_BASE + c.getInt();

		ZerionNotificationBuilder b = new ZerionNotificationBuilder(
				appContext, CONTACT_CHANNEL_ID);
		b.setSmallIcon(R.drawable.logo);
		b.setColorRes(R.color.zerion_primary);
		b.setContentTitle(appContext.getText(R.string.app_name));
		b.setContentText(appContext.getResources().getQuantityString(
				R.plurals.private_message_notification_text,
				count, count));
		b.setNumber(count);
		b.setNotificationCategory(CATEGORY_MESSAGE);
		if (mayAlert) setAlertProperties(b);

		setDeleteIntent(b,
				Uri.parse(CONTACT_URI + "/" + c.getInt()));

		Intent i = new Intent(appContext, ConversationActivity.class);
		i.putExtra(CONTACT_ID, c.getInt());
		i.setData(Uri.parse(CONTACT_URI + "/" + c.getInt()));
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		TaskStackBuilder t = TaskStackBuilder.create(appContext);
		t.addParentStack(ConversationActivity.class);
		t.addNextIntent(i);
		b.setContentIntent(t.getPendingIntent(nextRequestId++,
				getImmutableFlags(0)));

		boolean quickReplyEnabled = uiPrefs.getBoolean(
				com.professor.zerion.android.settings.NotificationsFragment
						.PREF_NOTIFY_QUICK_REPLY, true);
		if (quickReplyEnabled) {
			RemoteInput remoteInput = new RemoteInput.Builder(
					NotificationQuickReplyReceiver.KEY_REPLY_TEXT)
					.setLabel(appContext.getString(
							R.string.quick_reply_hint))
					.build();
			Intent replyIntent = new Intent(appContext,
					NotificationQuickReplyReceiver.class);
			replyIntent.putExtra(CONTACT_ID, c.getInt());
			int replyFlags = PendingIntent.FLAG_UPDATE_CURRENT;
			if (android.os.Build.VERSION.SDK_INT >= 31) {
				replyFlags |= PendingIntent.FLAG_MUTABLE;
			}
			PendingIntent replyPendingIntent = getBroadcast(
					appContext, nextRequestId++,
					replyIntent, replyFlags);
			NotificationCompat.Action replyAction =
					new NotificationCompat.Action.Builder(
							R.drawable.ic_reply,
							appContext.getString(
									R.string.quick_reply),
							replyPendingIntent)
							.addRemoteInput(remoteInput)
							.setAllowGeneratedReplies(false)
							.build();
			b.addAction(replyAction);
		}

		notificationManager.notify(notifId, b.build());
		activeContactNotificationIds.add(notifId);
	}

	@UiThread
	private void setAlertProperties(ZerionNotificationBuilder b) {
		long currentTime = clock.currentTimeMillis();
		if (currentTime - lastSound > SOUND_DELAY) {
			boolean sound = settings.getBoolean(PREF_NOTIFY_SOUND, true);
			String ringtoneUri = settings.get(PREF_NOTIFY_RINGTONE_URI);
			if (sound && !StringUtils.isNullOrEmpty(ringtoneUri)) {
				Uri uri = Uri.parse(ringtoneUri);
				if (!"file".equals(uri.getScheme())) b.setSound(uri);
			}
			b.setDefaults(getDefaults());
			lastSound = currentTime;
		}
	}

	@UiThread
	private int getDefaults() {
		int defaults = DEFAULT_LIGHTS;
		boolean sound = settings.getBoolean(PREF_NOTIFY_SOUND, true);
		String ringtoneUri = settings.get(PREF_NOTIFY_RINGTONE_URI);
		if (sound && (StringUtils.isNullOrEmpty(ringtoneUri) ||
				"file".equals(Uri.parse(ringtoneUri).getScheme())))
			defaults |= DEFAULT_SOUND;
		if (settings.getBoolean(PREF_NOTIFY_VIBRATION, true))
			defaults |= DEFAULT_VIBRATE;
		return defaults;
	}

	private void setDeleteIntent(ZerionNotificationBuilder b, Uri uri) {
		Intent i = new Intent(appContext, NotificationCleanupService.class);
		i.setData(uri);
		b.setDeleteIntent(PendingIntent.getService(appContext, nextRequestId++,
				i, getImmutableFlags(0)));
	}

	@Override
	public void clearAllContactNotifications() {
		androidExecutor.runOnUiThread(
				(Runnable) this::clearContactNotification);
	}

	@Override
	public void showGroupMessageNotification(GroupId g) {
		if (blockGroups) return;
		if (g.equals(blockedGroup)) return;
		groupCounts.add(g);
		updateGroupMessageNotification(true);
	}

	@Override
	public void clearGroupMessageNotification(GroupId g) {
		androidExecutor.runOnUiThread(() -> {
			if (groupCounts.removeAll(g) > 0)
				updateGroupMessageNotification(false);
		});
	}

	@UiThread
	private void updateGroupMessageNotification(boolean mayAlertAgain) {
		int groupTotal = groupCounts.getTotal();
		if (groupTotal == 0) {
			clearGroupMessageNotification();
		} else if (settings.getBoolean(PREF_NOTIFY_GROUP, true)) {
			ZerionNotificationBuilder b =
					new ZerionNotificationBuilder(appContext, GROUP_CHANNEL_ID);
			b.setSmallIcon(R.drawable.logo);
			b.setColorRes(R.color.zerion_primary);
			b.setContentTitle(appContext.getText(R.string.app_name));
			b.setContentText(appContext.getResources().getQuantityString(
					R.plurals.group_message_notification_text, groupTotal,
					groupTotal));
			b.setNumber(groupTotal);
			b.setNotificationCategory(CATEGORY_SOCIAL);
			if (mayAlertAgain) setAlertProperties(b);
			setDeleteIntent(b, GROUP_URI);
			Intent i = new Intent(appContext, NavDrawerActivity.class);
			i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
			i.setData(GROUP_URI);
			TaskStackBuilder t = TaskStackBuilder.create(appContext);
			t.addParentStack(NavDrawerActivity.class);
			t.addNextIntent(i);
			b.setContentIntent(t.getPendingIntent(nextRequestId++,
					getImmutableFlags(0)));
			notificationManager.notify(GROUP_MESSAGE_NOTIFICATION_ID,
					b.build());
		}
	}

	@Override
	public void clearAllGroupMessageNotifications() {
		androidExecutor.runOnUiThread(
				(Runnable) this::clearGroupMessageNotification);
	}

	@Override
	public void showGroupTrPostNotification(byte[] groupTrId) {
		androidExecutor.runOnUiThread(() -> {
			String hex = StringUtils.toHexString(groupTrId);
			if (hex.equals(blockedGroupTr)) return;
			if (!settings.getBoolean(PREF_NOTIFY_GROUP, true)) return;
			groupTrCounts.add(hex);
			postGroupTrNotification(groupTrId, hex, true);
		});
	}

	@Override
	public void clearGroupTrPostNotification(byte[] groupTrId) {
		androidExecutor.runOnUiThread(() -> {
			String hex = StringUtils.toHexString(groupTrId);
			if (groupTrCounts.removeAll(hex) > 0) {
				int id = groupTrNotificationId(hex);
				notificationManager.cancel(id);
				activeGroupTrNotificationIds.remove(id);
			}
		});
	}

	@UiThread
	private void clearAllGroupTrNotifications() {
		groupTrCounts.clear();
		for (int id : activeGroupTrNotificationIds) {
			notificationManager.cancel(id);
		}
		activeGroupTrNotificationIds.clear();
	}

	private static int groupTrNotificationId(String hex) {
		return GROUP_TR_NOTIFICATION_ID_BASE
				+ (hex.hashCode() & 0x0FFFFFFF);
	}

	@UiThread
	private void postGroupTrNotification(byte[] groupTrId, String hex,
			boolean mayAlertAgain) {
		int count = groupTrCounts.getCount(hex);
		if (count == 0) return;
		ZerionNotificationBuilder b =
				new ZerionNotificationBuilder(appContext, GROUP_CHANNEL_ID);
		b.setSmallIcon(R.drawable.logo);
		b.setColorRes(R.color.zerion_primary);
		b.setContentTitle(appContext.getText(R.string.app_name));
		b.setContentText(appContext.getResources().getQuantityString(
				R.plurals.group_message_notification_text, count, count));
		b.setNumber(count);
		b.setNotificationCategory(CATEGORY_SOCIAL);
		if (mayAlertAgain) setAlertProperties(b);
		Intent i = new Intent(appContext, GroupTrConversationActivity.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, hex);
		i.setData(Uri.parse("zerion://grouptr/" + hex));
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		TaskStackBuilder t = TaskStackBuilder.create(appContext);
		t.addParentStack(GroupTrConversationActivity.class);
		t.addNextIntent(i);
		b.setContentIntent(t.getPendingIntent(nextRequestId++,
				getImmutableFlags(0)));
		int id = groupTrNotificationId(hex);
		activeGroupTrNotificationIds.add(id);
		notificationManager.notify(id, b.build());
	}

	@Override
	public void blockGroupTrNotification(byte[] groupTrId) {
		androidExecutor.runOnUiThread(() ->
				blockedGroupTr = StringUtils.toHexString(groupTrId));
	}

	@Override
	public void unblockGroupTrNotification(byte[] groupTrId) {
		androidExecutor.runOnUiThread(() -> {
			String hex = StringUtils.toHexString(groupTrId);
			if (hex.equals(blockedGroupTr)) blockedGroupTr = null;
		});
	}

	@Override
	public void showContactAddedNotification(ContactId c) {
		androidExecutor.runOnUiThread(() -> showContactAddedNotification());
	}

	@UiThread
	private void showContactAddedNotification() {
		contactAddedTotal++;
		updateContactAddedNotification();
	}

	@UiThread
	private void updateContactAddedNotification() {
		ZerionNotificationBuilder b =
				new ZerionNotificationBuilder(appContext, CONTACT_CHANNEL_ID);
		b.setSmallIcon(R.drawable.logo);
		b.setColorRes(R.color.zerion_primary);
		b.setContentTitle(appContext.getText(R.string.app_name));
		b.setContentText(appContext.getResources().getQuantityString(
				R.plurals.contact_added_notification_text, contactAddedTotal,
				contactAddedTotal));
		b.setNotificationCategory(CATEGORY_MESSAGE);
		setAlertProperties(b);
		setDeleteIntent(b, CONTACT_ADDED_URI);
		Intent i = new Intent(appContext, NavDrawerActivity.class);
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		i.setData(CONTACT_URI);
		TaskStackBuilder t = TaskStackBuilder.create(appContext);
		t.addParentStack(NavDrawerActivity.class);
		t.addNextIntent(i);
		b.setContentIntent(
				t.getPendingIntent(nextRequestId++, getImmutableFlags(0)));

		notificationManager.notify(CONTACT_ADDED_NOTIFICATION_ID,
				b.build());
	}

	@Override
	public void clearAllContactAddedNotifications() {
		androidExecutor.runOnUiThread(this::clearContactAddedNotification);
	}

	@Override
	public void showSignInNotification() {
		if (blockSignInReminder) return;
		if (SDK_INT >= 26) {
			NotificationChannel channel = new NotificationChannel(
					REMINDER_CHANNEL_ID, appContext
					.getString(R.string.reminder_notification_channel_title),
					IMPORTANCE_LOW);
			channel.setLockscreenVisibility(VISIBILITY_SECRET);
			notificationManager.createNotificationChannel(channel);
		}

		NotificationCompat.Builder b =
				new NotificationCompat.Builder(appContext, REMINDER_CHANNEL_ID);
		b.setSmallIcon(R.drawable.logo);
		b.setColor(getColor(appContext, R.color.zerion_primary));
		b.setContentTitle(
				appContext.getText(R.string.reminder_notification_title));
		b.setContentText(
				appContext.getText(R.string.reminder_notification_text));
		b.setAutoCancel(true);
		b.setWhen(0);
		b.setPriority(PRIORITY_LOW);

		String actionTitle =
				appContext.getString(R.string.reminder_notification_dismiss);
		Intent i1 = new Intent(appContext, SignInReminderReceiver.class);
		i1.setAction(ACTION_DISMISS_REMINDER);
		PendingIntent actionIntent =
				getBroadcast(appContext, 0, i1, getImmutableFlags(0));
		b.addAction(0, actionTitle, actionIntent);

		Intent i = new Intent(appContext, SplashScreenActivity.class);
		i.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
		b.setContentIntent(getActivity(appContext, 0, i, getImmutableFlags(0)));

		notificationManager.notify(REMINDER_NOTIFICATION_ID, b.build());
	}

	@Override
	public void clearSignInNotification() {
		notificationManager.cancel(REMINDER_NOTIFICATION_ID);
	}

	@Override
	public void blockSignInNotification() {
		blockSignInReminder = true;
	}

	@Override
	public void unblockSignInNotification() {
		blockSignInReminder = false;
	}

	@Override
	public void blockNotification(GroupId g) {
		androidExecutor.runOnUiThread((Runnable) () -> blockedGroup = g);
	}

	@Override
	public void unblockNotification(GroupId g) {
		androidExecutor.runOnUiThread(() -> {
			if (g.equals(blockedGroup)) blockedGroup = null;
		});
	}

	@Override
	public void blockContactNotification(ContactId c) {
		androidExecutor.runOnUiThread((Runnable) () -> blockedContact = c);
	}

	@Override
	public void unblockContactNotification(ContactId c) {
		androidExecutor.runOnUiThread(() -> {
			if (c.equals(blockedContact)) blockedContact = null;
		});
	}

	@Override
	public void blockAllGroupMessageNotifications() {
		androidExecutor.runOnUiThread((Runnable) () -> blockGroups = true);
	}

	@Override
	public void unblockAllGroupMessageNotifications() {
		androidExecutor.runOnUiThread((Runnable) () -> blockGroups = false);
	}

	private void checkForIncomingCall(PrivateMessageReceivedEvent event) {
		androidExecutor.runOnBackgroundThread(() -> {
			try {
				String messageText = messagingManager.getMessageText(
						event.getMessageHeader().getId());

				if (messageText == null || !isVoiceCallSignal(messageText)) {
					return;
				}
				String decoded = decodeVoiceCallSignal(messageText);
				if (decoded == null) return;

				String[] parts = decoded.split(":");
				if (parts.length < 2) return;

				String signalType = parts[1];
				if ("CALL_OFFER".equals(signalType)) {
					String remoteCallId = parts.length > 2 ? parts[2] : null;
					String voiceCallKey = parts.length > 3 ? parts[3] : null;

					ContactId contactId = event.getContactId();
					Contact contact = contactManager.getContact(contactId);
					androidExecutor.runOnUiThread(() -> {
						Intent intent = new Intent(appContext,
								com.professor.zerion.android.conversation.voice.VoiceCallActivity.class);
						intent.putExtra("contact_id", contactId.getInt());
						intent.putExtra("is_incoming", true);
						if (remoteCallId != null) {
							intent.putExtra("call_id", remoteCallId);
						}
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
						appContext.startActivity(intent);
					});
				}
			} catch (DbException e) {
			}
		});
	}

	private boolean isVoiceCallSignal(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		try {
			byte[] decodedBytes = android.util.Base64.decode(text, android.util.Base64.NO_WRAP);
			String decoded = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
			boolean isSignal = decoded.startsWith("VOICE_CALL:");
			java.util.Arrays.fill(decodedBytes, (byte) 0);
			return isSignal;
		} catch (Exception e) {
			return false;
		}
	}

	@Nullable
	private String decodeVoiceCallSignal(String text) {
		try {
			byte[] decodedBytes = android.util.Base64.decode(text, android.util.Base64.NO_WRAP);
			String decoded = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
			java.util.Arrays.fill(decodedBytes, (byte) 0);
			return decoded;
		} catch (Exception e) {
			return null;
		}
	}

	private void handleIncomingVoiceCall(ContactId contactId,
			VoiceSignalHeader header) {
		androidExecutor.runOnBackgroundThread(() -> {
			try {
				Contact contact = contactManager.getContact(contactId);
				String callId = header.getCallId();
				String rawPayload = header.getPayload();

				boolean isVideoCall = false;
				if (rawPayload != null) {
					String[] parts = rawPayload.split("\\|");
					if (parts.length >= 2 &&
							"VIDEO".equals(parts[parts.length - 1])) {
						isVideoCall = true;
					}
				}

				boolean voiceEnabled = uiPrefs.getBoolean(
						com.professor.zerion.android.settings.SecurityFragment
								.PREF_VOICE_CALLS_ENABLED, false);
				boolean videoEnabled = uiPrefs.getBoolean(
						com.professor.zerion.android.settings.SecurityFragment
								.PREF_VIDEO_CALLS_ENABLED, false);

				if (!voiceEnabled || (isVideoCall && !videoEnabled)) {
					String reason = (isVideoCall && !videoEnabled && voiceEnabled)
							? "video_disabled" : "calls_disabled";
					rejectIncomingCall(contact, callId, reason);
					return;
				}

				if (rawPayload != null) {
					String voiceCallKeyHex = rawPayload;
					String ephemeralHex = null;
					String[] parts = rawPayload.split("\\|");
					voiceCallKeyHex = parts[0];
					if (parts.length >= 2) {
						if ("VIDEO".equals(parts[parts.length - 1])) {
							if (parts.length >= 3) {
								ephemeralHex = parts[1];
							}
						} else {
							ephemeralHex = parts[1];
						}
					}
					try {
						SecretKey key = new SecretKey(
								StringUtils.fromHexString(voiceCallKeyHex));
						VoiceCallKeyHolder.setKey(key);
					} catch (Exception e) {
					}
					if (ephemeralHex != null) {
						try {
							VoiceCallKeyHolder.setRemoteEphemeral(
									StringUtils.fromHexString(ephemeralHex));
						} catch (Exception ignored) {
						}
					}
				}

				final boolean videoCall = isVideoCall;
				androidExecutor.runOnUiThread(() -> {
					Intent intent = new Intent(appContext,
							com.professor.zerion.android.conversation.voice.VoiceCallActivity.class);
					intent.putExtra(
							com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CONTACT_ID,
							contactId.getInt());
					intent.putExtra(
							com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_IS_INCOMING,
							true);
					intent.putExtra("auto_video", videoCall);
					if (callId != null) {
						intent.putExtra(
								com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CALL_ID,
								callId);
					}
					intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
					appContext.startActivity(intent);
				});
			} catch (DbException e) {
			}
		});
	}

	private void rejectIncomingCall(Contact contact, String callId,
			String reason) {
		if (callId == null) return;
		androidExecutor.runOnBackgroundThread(() -> {
			try {
				GroupId groupId =
						messagingManager.getContactGroup(contact).getId();
				long timestamp = clock.currentTimeMillis();
				VoiceSignal signal = voiceSignalFactory.createCallReject(
						groupId, timestamp, callId, reason);
				messagingManager.addLocalVoiceSignal(signal);
			} catch (Exception ignored) {
			}
		});
	}
}
