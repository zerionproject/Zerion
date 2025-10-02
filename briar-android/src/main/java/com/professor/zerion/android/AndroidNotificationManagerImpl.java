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
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.lifecycle.ServiceException;
import org.briarproject.bramble.api.mailbox.MailboxStatus;
import org.briarproject.bramble.api.mailbox.event.MailboxProblemEvent;
import org.briarproject.bramble.api.mailbox.event.OwnMailboxConnectionStatusEvent;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.StringUtils;
import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.hotspot.HotspotActivity;
import com.professor.zerion.android.login.SignInReminderReceiver;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.privategroup.conversation.GroupActivity;
import com.professor.zerion.android.splash.SplashScreenActivity;
import com.professor.zerion.android.util.BriarNotificationBuilder;
import com.professor.zerion.android.api.AndroidNotificationManager;
import org.briarproject.briar.api.conversation.ConversationResponse;
import org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent;
import org.briarproject.briar.api.privategroup.event.GroupMessageAddedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

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
import static com.professor.zerion.android.activity.BriarActivity.GROUP_ID;
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

	// Notification IDs
	private static final int PRIVATE_MESSAGE_NOTIFICATION_ID = 2;
	private static final int GROUP_MESSAGE_NOTIFICATION_ID = 3;
	private static final int CONTACT_ADDED_NOTIFICATION_ID = 4;
	private static final int SIGN_IN_NOTIFICATION_ID = 5;
	private static final int REMINDER_NOTIFICATION_ID = 6;

	private final SettingsManager settingsManager;
	private final AndroidExecutor androidExecutor;
	private final Clock clock;
	private final Context appContext;
	private final NotificationManager notificationManager;
	private final AtomicBoolean used = new AtomicBoolean(false);

	// The following must only be accessed on the main UI thread
	private final Multiset<ContactId> contactCounts = new Multiset<>();
	private final Multiset<GroupId> groupCounts = new Multiset<>();
	private int contactAddedTotal = 0;
	private int nextRequestId = 0;
	@Nullable
	private ContactId blockedContact = null;
	@Nullable
	private GroupId blockedGroup = null;
	private boolean blockSignInReminder = false;
	private boolean blockGroups = false;
	private long lastSound = 0;

	private volatile Settings settings = new Settings();

	@Inject
	AndroidNotificationManagerImpl(SettingsManager settingsManager,
			AndroidExecutor androidExecutor, Application app, Clock clock) {
		this.settingsManager = settingsManager;
		this.androidExecutor = androidExecutor;
		this.clock = clock;
		appContext = app.getApplicationContext();
		notificationManager = (NotificationManager)
				appContext.getSystemService(NOTIFICATION_SERVICE);
	}

	@Override
	public void startService() throws ServiceException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		// Load settings
		try {
			settings = settingsManager.getSettings(SETTINGS_NAMESPACE);
		} catch (DbException e) {
			throw new ServiceException(e);
		}
		if (SDK_INT >= 26) {
			// Create notification channels
			Callable<Void> task = () -> {
				createNotificationChannel(CONTACT_CHANNEL_ID,
						R.string.contact_list_button);
				createNotificationChannel(GROUP_CHANNEL_ID,
						R.string.groups_button);
				createNotificationChannel(FORUM_CHANNEL_ID,
						R.string.forums_button);
				createNotificationChannel(BLOG_CHANNEL_ID,
						R.string.blogs_button);
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
		nc.setLightColor(getColor(appContext, R.color.briar_lime_400));
		notificationManager.createNotificationChannel(nc);
	}

	@Override
	public void stopService() throws ServiceException {
		// Clear all notifications
		Future<Void> f = androidExecutor.runOnUiThread(() -> {
			clearContactNotification();
			clearGroupMessageNotification();
			clearForumPostNotification();
			clearBlogPostNotification();
			clearContactAddedNotification();
			clearMailboxProblemNotification();
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
		notificationManager.cancel(PRIVATE_MESSAGE_NOTIFICATION_ID);
	}

	@UiThread
	private void clearGroupMessageNotification() {
		groupCounts.clear();
		notificationManager.cancel(GROUP_MESSAGE_NOTIFICATION_ID);
	}

	@UiThread
	private void clearForumPostNotification() {
		// Forums have been removed from the app
	}

	@UiThread
	private void clearBlogPostNotification() {
		// Blogs have been removed from the app
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
				// don't show notification for own auto-decline responses
				if (r.isAutoDecline()) return;
			}
			showContactNotification(p.getContactId());
		} else if (e instanceof GroupMessageAddedEvent) {
			GroupMessageAddedEvent g = (GroupMessageAddedEvent) e;
			if (!g.isLocal()) showGroupMessageNotification(g.getGroupId());
		} else if (e instanceof ContactAddedEvent) {
			ContactAddedEvent c = (ContactAddedEvent) e;
			// Don't show notifications for contacts added in person
			if (!c.isVerified()) showContactAddedNotification();
		} else if (e instanceof MailboxProblemEvent) {
			showMailboxProblemNotification();
		} else if (e instanceof OwnMailboxConnectionStatusEvent) {
			MailboxStatus s = ((OwnMailboxConnectionStatusEvent) e).getStatus();
			if (s.getAttemptsSinceSuccess() == 0) {
				clearMailboxProblemNotification();
			}
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
		int icon = locked ? R.drawable.notification_lock :
				R.drawable.notification_ongoing;
		// Ongoing foreground notification that shows BriarService is running
		NotificationCompat.Builder b =
				new NotificationCompat.Builder(appContext, ONGOING_CHANNEL_ID);
		b.setSmallIcon(icon);
		b.setColor(getColor(appContext, R.color.briar_primary));
		b.setContentTitle(appContext.getText(title));
		b.setContentText(appContext.getText(text));
		b.setWhen(0); // Don't show the time
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
		updateContactNotification(true);
	}

	@Override
	public void clearContactNotification(ContactId c) {
		androidExecutor.runOnUiThread(() -> {
			if (contactCounts.removeAll(c) > 0)
				updateContactNotification(false);
		});
	}

	@UiThread
	private void updateContactNotification(boolean mayAlertAgain) {
		int contactTotal = contactCounts.getTotal();
		if (contactTotal == 0) {
			clearContactNotification();
		} else if (settings.getBoolean(PREF_NOTIFY_PRIVATE, true)) {
			BriarNotificationBuilder b = new BriarNotificationBuilder(
					appContext, CONTACT_CHANNEL_ID);
			b.setSmallIcon(R.drawable.notification_private_message);
			b.setColorRes(R.color.briar_primary);
			b.setContentTitle(appContext.getText(R.string.app_name));
			b.setContentText(appContext.getResources().getQuantityString(
					R.plurals.private_message_notification_text, contactTotal,
					contactTotal));
			b.setNumber(contactTotal);
			b.setNotificationCategory(CATEGORY_MESSAGE);
			if (mayAlertAgain) setAlertProperties(b);
			setDeleteIntent(b, CONTACT_URI);
			Set<ContactId> contacts = contactCounts.keySet();
			if (contacts.size() == 1) {
				// Touching the notification shows the relevant conversation
				Intent i = new Intent(appContext, ConversationActivity.class);
				ContactId c = contacts.iterator().next();
				i.putExtra(CONTACT_ID, c.getInt());
				i.setData(Uri.parse(CONTACT_URI + "/" + c.getInt()));
				i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
				TaskStackBuilder t = TaskStackBuilder.create(appContext);
				t.addParentStack(ConversationActivity.class);
				t.addNextIntent(i);
				b.setContentIntent(t.getPendingIntent(nextRequestId++,
						getImmutableFlags(0)));
			} else {
				// Touching the notification shows the contact list
				Intent i = new Intent(appContext, NavDrawerActivity.class);
				i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
				i.setData(CONTACT_URI);
				TaskStackBuilder t = TaskStackBuilder.create(appContext);
				t.addParentStack(NavDrawerActivity.class);
				t.addNextIntent(i);
				b.setContentIntent(t.getPendingIntent(nextRequestId++,
						getImmutableFlags(0)));
			}
			notificationManager.notify(PRIVATE_MESSAGE_NOTIFICATION_ID,
					b.build());
		}
	}

	@UiThread
	private void setAlertProperties(BriarNotificationBuilder b) {
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

	private void setDeleteIntent(BriarNotificationBuilder b, Uri uri) {
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
			BriarNotificationBuilder b =
					new BriarNotificationBuilder(appContext, GROUP_CHANNEL_ID);
			b.setSmallIcon(R.drawable.notification_private_group);
			b.setColorRes(R.color.briar_primary);
			b.setContentTitle(appContext.getText(R.string.app_name));
			b.setContentText(appContext.getResources().getQuantityString(
					R.plurals.group_message_notification_text, groupTotal,
					groupTotal));
			b.setNumber(groupTotal);
			b.setNotificationCategory(CATEGORY_SOCIAL);
			if (mayAlertAgain) setAlertProperties(b);
			setDeleteIntent(b, GROUP_URI);
			Set<GroupId> groups = groupCounts.keySet();
			if (groups.size() == 1) {
				// Touching the notification shows the relevant group
				Intent i = new Intent(appContext, GroupActivity.class);
				GroupId g = groups.iterator().next();
				i.putExtra(GROUP_ID, g.getBytes());
				String idHex = StringUtils.toHexString(g.getBytes());
				i.setData(Uri.parse(GROUP_URI + "/" + idHex));
				i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
				TaskStackBuilder t = TaskStackBuilder.create(appContext);
				t.addParentStack(GroupActivity.class);
				t.addNextIntent(i);
				b.setContentIntent(t.getPendingIntent(nextRequestId++,
						getImmutableFlags(0)));
			} else {
				// Touching the notification shows the group list
				Intent i = new Intent(appContext, NavDrawerActivity.class);
				i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
				i.setData(GROUP_URI);
				TaskStackBuilder t = TaskStackBuilder.create(appContext);
				t.addParentStack(NavDrawerActivity.class);
				t.addNextIntent(i);
				b.setContentIntent(t.getPendingIntent(nextRequestId++,
						getImmutableFlags(0)));
			}
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
	public void showForumPostNotification(GroupId g) {
		// Forums have been removed from the app
	}

	@Override
	public void clearForumPostNotification(GroupId g) {
		// Forums have been removed from the app
	}

	@UiThread
	private void updateForumPostNotification(boolean mayAlertAgain) {
		// Forums have been removed from the app
	}

	@Override
	public void clearAllForumPostNotifications() {
		// Forums have been removed from the app
	}

	@Override
	public void showBlogPostNotification(GroupId g) {
		// Blogs have been removed from the app
	}

	@Override
	public void clearBlogPostNotification(GroupId g) {
		// Blogs have been removed from the app
	}

	@UiThread
	private void updateBlogPostNotification(boolean mayAlertAgain) {
		// Blogs have been removed from the app
	}

	@Override
	public void clearAllBlogPostNotifications() {
		// Blogs have been removed from the app
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
		BriarNotificationBuilder b =
				new BriarNotificationBuilder(appContext, CONTACT_CHANNEL_ID);
		b.setSmallIcon(R.drawable.notification_contact_added);
		b.setColorRes(R.color.briar_primary);
		b.setContentTitle(appContext.getText(R.string.app_name));
		b.setContentText(appContext.getResources().getQuantityString(
				R.plurals.contact_added_notification_text, contactAddedTotal,
				contactAddedTotal));
		b.setNotificationCategory(CATEGORY_MESSAGE);
		setAlertProperties(b);
		setDeleteIntent(b, CONTACT_ADDED_URI);
		// Touching the notification shows the contact list
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
		b.setSmallIcon(R.drawable.notification_signout);
		b.setColor(getColor(appContext, R.color.briar_primary));
		b.setContentTitle(
				appContext.getText(R.string.reminder_notification_title));
		b.setContentText(
				appContext.getText(R.string.reminder_notification_text));
		b.setAutoCancel(true);
		b.setWhen(0); // Don't show the time
		b.setPriority(PRIORITY_LOW);

		// Add a 'Dismiss' action
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
	public void blockForumPostNotification(GroupId g) {
		// Forums have been removed from the app
	}

	@Override
	public void unblockForumPostNotification(GroupId g) {
		// Forums have been removed from the app
	}

	@Override
	public void blockAllForumPostNotifications() {
		// Forums have been removed from the app
	}

	@Override
	public void unblockAllForumPostNotifications() {
		// Forums have been removed from the app
	}

	@Override
	public void blockAllGroupMessageNotifications() {
		androidExecutor.runOnUiThread((Runnable) () -> blockGroups = true);
	}

	@Override
	public void unblockAllGroupMessageNotifications() {
		androidExecutor.runOnUiThread((Runnable) () -> blockGroups = false);
	}

	@Override
	public void blockBlogPostNotification(GroupId g) {
		// Blogs have been removed from the app
	}

	@Override
	public void unblockBlogPostNotification(GroupId g) {
		// Blogs have been removed from the app
	}

	@Override
	public void blockAllBlogPostNotifications() {
		// Blogs have been removed from the app
	}

	@Override
	public void unblockAllBlogPostNotifications() {
		// Blogs have been removed from the app
	}

	@Override
	public void showHotspotNotification() {
		// Hotspot functionality kept in the app - no changes needed here
		// But we need the missing constants
		final String HOTSPOT_CHANNEL_ID = "hotspot";
		final int HOTSPOT_NOTIFICATION_ID = 11;

		if (SDK_INT >= 26) {
			String channelTitle = appContext
					.getString(R.string.hotspot_notification_channel_title);
			NotificationChannel channel = new NotificationChannel(
					HOTSPOT_CHANNEL_ID, channelTitle, IMPORTANCE_LOW);
			channel.setLockscreenVisibility(VISIBILITY_SECRET);
			notificationManager.createNotificationChannel(channel);
		}
		BriarNotificationBuilder b =
				new BriarNotificationBuilder(appContext, HOTSPOT_CHANNEL_ID);
		b.setSmallIcon(R.drawable.notification_hotspot);
		b.setColorRes(R.color.briar_brand_green);
		b.setContentTitle(
				appContext.getText(R.string.hotspot_notification_title));
		b.setNotificationCategory(CATEGORY_SERVICE);
		b.setOngoing(true);
		b.setShowWhen(true);

		String actionTitle =
				appContext.getString(R.string.hotspot_button_stop_sharing);
		Intent i = new Intent(appContext, HotspotActivity.class);
		i.addFlags(FLAG_ACTIVITY_SINGLE_TOP);
		i.setAction(ACTION_STOP_HOTSPOT);
		PendingIntent actionIntent =
				getActivity(appContext, 0, i, getImmutableFlags(0));
		b.addAction(R.drawable.ic_portable_wifi_off, actionTitle, actionIntent);
		notificationManager.notify(HOTSPOT_NOTIFICATION_ID, b.build());
	}

	@Override
	public void clearHotspotNotification() {
		final int HOTSPOT_NOTIFICATION_ID = 11;
		notificationManager.cancel(HOTSPOT_NOTIFICATION_ID);
	}

	@Override
	public void showMailboxProblemNotification() {
		// Mailbox functionality has been removed from the app
	}

	@Override
	public void clearMailboxProblemNotification() {
		// Mailbox functionality has been removed from the app
	}
}
