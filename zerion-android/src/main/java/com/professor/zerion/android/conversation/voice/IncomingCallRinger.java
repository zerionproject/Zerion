package com.professor.zerion.android.conversation.voice;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.professor.zerion.R;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.settings.NotificationDisguise;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.ContactId;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

import static com.professor.zerion.android.navdrawer.NavDrawerActivity.CONTACT_URI;

@UiThread
@NotThreadSafe
@NotNullByDefault
public class IncomingCallRinger
		implements Application.ActivityLifecycleCallbacks {

	public interface Rejecter {

		void reject(int contactId, String callId);

		void busy(int contactId, String callId);
	}

	public static final long RING_TIMEOUT_MS = 60_000;

	public static final String RING_TAG = "zerion.call.ringing";
	public static final String RING_CHANNEL_ID =
			CallNotification.RING_CHANNEL_ID;
	public static final String MISSED_TAG = "zerion.call.missed";
	public static final String FAILED_TAG = "zerion.call.failed";
	public static final String HINT_TAG = "zerion.call.full_screen_hint";
	static final String MISSED_CHANNEL_ID = "missed_call_channel";
	static final String PREF_FULL_SCREEN_HINT_SHOWN =
			"pref_full_screen_call_hint_shown";
	static final String FULL_SCREEN_SETTINGS =
			"android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT";
	private static final int MAX_ENDED = 64;
	private static final int HINT_REQUEST = 0x4811;

	private final class Ringing implements Runnable {

		private final int contactId;
		private final String callId;
		private final boolean video;
		private final boolean silent;
		private boolean shown = false;

		private Ringing(int contactId, String callId, boolean video,
				boolean silent) {
			this.contactId = contactId;
			this.callId = callId;
			this.video = video;
			this.silent = silent;
		}

		private boolean is(int contact, @Nullable String call) {
			return contactId == contact && callId.equals(normalise(call));
		}

		@Override
		public void run() {
			if (ringing == this) missed(this);
		}
	}

	private final Context context;
	private final NotificationManager notificationManager;
	private final SharedPreferences prefs;
	private final Rejecter rejecter;
	private final BooleanSupplier fullScreenAllowed;
	private final int sdk;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final LinkedHashSet<String> ended = new LinkedHashSet<>();

	@Nullable
	private Ringing ringing;
	@Nullable
	private WeakReference<Activity> resumed;
	private boolean callInProgress = false;
	private int activeContact = -1;
	@Nullable
	private String activeCallId;

	public IncomingCallRinger(Context context,
			NotificationManager notificationManager, SharedPreferences prefs,
			Rejecter rejecter) {
		this(context, notificationManager, prefs, rejecter,
				() -> Build.VERSION.SDK_INT < 34
						|| notificationManager.canUseFullScreenIntent());
	}

	IncomingCallRinger(Context context,
			NotificationManager notificationManager, SharedPreferences prefs,
			Rejecter rejecter, BooleanSupplier fullScreenAllowed) {
		this(context, notificationManager, prefs, rejecter, fullScreenAllowed,
				Build.VERSION.SDK_INT);
	}

	IncomingCallRinger(Context context,
			NotificationManager notificationManager, SharedPreferences prefs,
			Rejecter rejecter, BooleanSupplier fullScreenAllowed, int sdk) {
		this.sdk = sdk;
		this.context = context;
		this.notificationManager = notificationManager;
		this.prefs = prefs;
		this.rejecter = rejecter;
		this.fullScreenAllowed = fullScreenAllowed;
	}

	private static String normalise(@Nullable String callId) {
		return callId == null ? "" : callId;
	}

	private static String key(int contactId, @Nullable String callId) {
		return contactId + "/" + normalise(callId);
	}

	public void offer(int contactId, @Nullable String callId, boolean video) {
		Ringing current = ringing;
		boolean overtaken = ended.remove(key(contactId, callId));
		if (overtaken) {
			VoiceCallKeyHolder.clearFor(contactId, callId);
			showMissedCall(contactId, video, false);
			return;
		}
		if (callInProgress
				|| (current != null && current.contactId != contactId)) {
			VoiceCallKeyHolder.clearFor(contactId, callId);
			rejecter.busy(contactId, normalise(callId));
			showMissedCall(contactId, video, false);
			return;
		}
		Activity top = resumedActivity();
		if (top != null) {
			try {
				top.startActivity(CallIntents.incomingCall(top, contactId,
						callId, video));
				return;
			} catch (RuntimeException e) {
			}
		}
		if (current != null) missed(current);
		boolean silent = !prefs.getBoolean(
				AndroidNotificationManager.PREF_NOTIFY_VOICE_CALLS, true)
				|| ringChannelBlocked();
		Ringing r = new Ringing(contactId, normalise(callId), video, silent);
		ringing = r;
		handler.postDelayed(r, RING_TIMEOUT_MS);
		if (silent) return;
		CallNotification calls = new CallNotification(context);
		calls.createRingChannel();
		ContactId caller = new ContactId(contactId);
		try {
			notificationManager.notify(RING_TAG, contactId,
					calls.buildRinging(caller, r.callId, video, true));
		} catch (RuntimeException e) {
			notificationManager.notify(RING_TAG, contactId,
					calls.buildRinging(caller, r.callId, video, false));
		}
		offerFullScreenSettings();
	}

	public boolean isRinging(int contactId, @Nullable String callId) {
		Ringing r = ringing;
		return r != null && r.is(contactId, callId);
	}

	public void callStarted(int contactId, @Nullable String callId) {
		callInProgress = true;
		activeContact = contactId;
		activeCallId = callId;
		Ringing r = ringing;
		if (r != null) {
			if (r.is(contactId, callId)) stop(r);
			else missed(r);
		}
		notificationManager.cancel(RING_TAG, contactId);
	}

	public void callStopped() {
		callInProgress = false;
		activeContact = -1;
		activeCallId = null;
	}

	public boolean hasCall(int contactId, @Nullable String callId) {
		if (!callInProgress || activeContact != contactId) return false;
		String active = activeCallId;
		return callId == null || active == null || active.equals(callId);
	}

	public void callUnavailable(int contactId, @Nullable String callId) {
		Ringing r = ringing;
		if (r != null && r.is(contactId, callId)) missed(r);
		notificationManager.cancel(RING_TAG, contactId);
	}

	public void remoteEnded(int contactId, @Nullable String callId) {
		Ringing r = ringing;
		if (r != null && r.is(contactId, callId)) {
			missed(r);
			return;
		}
		ended.add(key(contactId, callId));
		Iterator<String> it = ended.iterator();
		while (ended.size() > MAX_ENDED && it.hasNext()) {
			it.next();
			it.remove();
		}
	}

	public void decline(int contactId, @Nullable String callId) {
		notificationManager.cancel(RING_TAG, contactId);
		Ringing r = ringing;
		if (r == null || !r.is(contactId, callId)) return;
		stop(r);
		VoiceCallKeyHolder.clearFor(contactId, r.callId);
		rejecter.reject(contactId, r.callId);
	}

	public void createChannels() {
		new CallNotification(context).createRingChannel();
		createMissedChannel();
	}

	public void clear() {
		Ringing r = ringing;
		if (r != null) stop(r);
		VoiceCallKeyHolder.clear();
		ended.clear();
	}

	private void stop(Ringing r) {
		handler.removeCallbacks(r);
		if (ringing == r) ringing = null;
		notificationManager.cancel(RING_TAG, r.contactId);
	}

	private void missed(Ringing r) {
		stop(r);
		VoiceCallKeyHolder.clearFor(r.contactId, r.callId);
		showMissedCall(r.contactId, r.video, r.silent);
	}

	public void showMissedCall(int contactId, boolean video) {
		showMissedCall(contactId, video, false);
	}

	private void showMissedCall(int contactId, boolean video,
			boolean silent) {
		postCallNotice(MISSED_TAG, contactId, video,
				R.string.call_notification_missed_title,
				NotificationCompat.CATEGORY_MISSED_CALL, silent);
	}

	public void showCallFailed(int contactId, boolean video,
			@StringRes int reason) {
		postCallNotice(FAILED_TAG, contactId, video, reason,
				NotificationCompat.CATEGORY_STATUS, false);
	}

	private void postCallNotice(String tag, int contactId, boolean video,
			@StringRes int title, String category, boolean silent) {
		createMissedChannel();
		Intent open = new Intent(context, ConversationActivity.class);
		open.putExtra(ConversationActivity.CONTACT_ID, contactId);
		open.setData(Uri.parse(CONTACT_URI + "/" + contactId));
		open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		TaskStackBuilder stack = TaskStackBuilder.create(context);
		stack.addParentStack(ConversationActivity.class);
		stack.addNextIntent(open);
		PendingIntent pending = stack.getPendingIntent(contactId,
				PendingIntent.FLAG_UPDATE_CURRENT
						| PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder b =
				new NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
						.setSmallIcon(NotificationDisguise.smallIcon(context,
								R.drawable.ic_call_received))
						.setContentTitle(NotificationDisguise.title(context,
								title))
						.setContentText(NotificationDisguise.text(context,
								video ? R.string.call_notification_video_text
										: R.string.call_notification_voice_text))
						.setCategory(category)
						.setPriority(NotificationCompat.PRIORITY_DEFAULT)
						.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
						.setContentIntent(pending)
						.setSilent(silent)
						.setAutoCancel(true);
		notificationManager.notify(tag, contactId, b.build());
	}

	private void offerFullScreenSettings() {
		if (fullScreenAllowed.getAsBoolean()) return;
		if (prefs.getBoolean(PREF_FULL_SCREEN_HINT_SHOWN, false)) return;
		prefs.edit().putBoolean(PREF_FULL_SCREEN_HINT_SHOWN, true).apply();
		createMissedChannel();
		Intent settings = fullScreenSettings(context, sdk);
		PendingIntent pending = PendingIntent.getActivity(context,
				HINT_REQUEST,
				settings, PendingIntent.FLAG_UPDATE_CURRENT
						| PendingIntent.FLAG_IMMUTABLE);
		CharSequence text = NotificationDisguise.text(context,
				R.string.call_full_screen_hint_text);
		NotificationCompat.Builder b =
				new NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
						.setSmallIcon(NotificationDisguise.smallIcon(context,
								R.drawable.ic_phone_white))
						.setContentTitle(NotificationDisguise.title(context,
								R.string.call_full_screen_hint_title))
						.setContentText(text)
						.setStyle(new NotificationCompat.BigTextStyle()
								.bigText(text))
						.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
						.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
						.setSilent(true)
						.setGroup(HINT_TAG)
						.setGroupSummary(true)
						.setContentIntent(pending)
						.setAutoCancel(true);
		notificationManager.notify(HINT_TAG, 0, b.build());
	}

	static Intent fullScreenSettings(Context context, int sdk) {
		Intent settings;
		if (sdk >= 34) {
			settings = new Intent(FULL_SCREEN_SETTINGS,
					Uri.parse("package:" + context.getPackageName()));
		} else {
			settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
			settings.putExtra(Settings.EXTRA_APP_PACKAGE,
					context.getPackageName());
		}
		settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return settings;
	}

	private void createMissedChannel() {
		if (Build.VERSION.SDK_INT < 26) return;
		NotificationChannel channel = new NotificationChannel(
				MISSED_CHANNEL_ID,
				context.getString(R.string.missed_call_channel),
				NotificationManager.IMPORTANCE_DEFAULT);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		notificationManager.createNotificationChannel(channel);
	}

	private boolean ringChannelBlocked() {
		if (Build.VERSION.SDK_INT < 26) return false;
		try {
			NotificationChannel ring = notificationManager
					.getNotificationChannel(RING_CHANNEL_ID);
			return ring != null && ring.getImportance()
					== NotificationManager.IMPORTANCE_NONE;
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Nullable
	private Activity resumedActivity() {
		WeakReference<Activity> ref = resumed;
		Activity a = ref == null ? null : ref.get();
		if (a == null || a.isFinishing()) return null;
		return a;
	}

	@Override
	public void onActivityResumed(Activity activity) {
		resumed = new WeakReference<>(activity);
		Ringing r = ringing;
		if (r == null || r.shown || activity instanceof VoiceCallActivity) {
			return;
		}
		r.shown = true;
		try {
			activity.startActivity(CallIntents.incomingCall(activity,
					r.contactId, r.callId, r.video));
		} catch (RuntimeException e) {
			r.shown = false;
		}
	}

	@Override
	public void onActivityPaused(Activity activity) {
		WeakReference<Activity> ref = resumed;
		if (ref != null && ref.get() == activity) resumed = null;
	}

	@Override
	public void onActivityCreated(Activity activity,
			@Nullable Bundle savedInstanceState) {
	}

	@Override
	public void onActivityStarted(Activity activity) {
	}

	@Override
	public void onActivityStopped(Activity activity) {
	}

	@Override
	public void onActivitySaveInstanceState(Activity activity,
			Bundle outState) {
	}

	@Override
	public void onActivityDestroyed(Activity activity) {
	}
}
