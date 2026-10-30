package com.professor.zerion.android.conversation.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import com.professor.zerion.R;
import com.professor.zerion.android.settings.NotificationDisguise;

import org.zerionproject.core.api.contact.ContactId;

class CallNotification {

	static final String LEGACY_CHANNEL_ID = "voice_call_channel";
	static final String RING_CHANNEL_ID = "voice_call_ring";
	private static final String CHANNEL_ID_ONGOING = "voice_call_ongoing_channel";

	static final String GROUP = "zerion.call";

	private final Context context;

	CallNotification(Context context) {
		this.context = context;
	}

	private Intent callIntent(ContactId contactId, boolean isIncoming,
			String callId) {
		Intent intent = new Intent(context, VoiceCallActivity.class);
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contactId.getInt());
		intent.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, isIncoming);
		intent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		return intent;
	}

	private PendingIntent acceptIntent(Intent intent) {
		Intent acceptIntent = new Intent(intent);
		acceptIntent.putExtra(VoiceCallActivity.EXTRA_ANSWER, true);
		return PendingIntent.getActivity(context, 1, acceptIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private PendingIntent declineIntent(ContactId contactId, String callId) {
		Intent declineIntent = new Intent(context, VoiceCallService.class);
		declineIntent.setAction(CallIntents.ACTION_DECLINE_CALL);
		declineIntent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID,
				contactId.getInt());
		declineIntent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		return PendingIntent.getService(context, 2, declineIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	Notification build(ContactId contactId, boolean isIncoming, String callId,
			VoiceCallService.CallState callState, boolean videoActive) {
		Intent intent = callIntent(contactId, isIncoming, callId);

		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
				intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		if (isIncoming && callState == VoiceCallService.CallState.RINGING) {
			return buildRinging(contactId, callId, videoActive, true, false);
		}
		String title = context.getString(
				R.string.call_notification_ongoing_title);
		String text = context.getString(videoActive
				? R.string.call_notification_video_text
				: R.string.call_notification_voice_text);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
				CHANNEL_ID_ONGOING)
				.setContentTitle(title)
				.setContentText(text)
				.setSmallIcon(R.drawable.ic_phone_white)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setCategory(NotificationCompat.CATEGORY_CALL)
				.setGroup(GROUP)
				.setOngoing(true)
				.setAutoCancel(false)
				.setContentIntent(pendingIntent);

		return builder.build();
	}

	Notification buildRinging(ContactId contactId, String callId,
			boolean video, boolean callStyle) {
		return buildRinging(contactId, callId, video, callStyle, true);
	}

	Notification buildRinging(ContactId contactId, String callId,
			boolean video, boolean callStyle, boolean alert) {
		Intent intent = callIntent(contactId, true, callId);
		intent.putExtra(CallIntents.EXTRA_AUTO_VIDEO, video);
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
				intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		boolean disguised = NotificationDisguise.active(context);
		CharSequence title = NotificationDisguise.title(context,
				R.string.call_notification_incoming_title);
		CharSequence text = NotificationDisguise.text(context, video
				? R.string.call_notification_video_text
				: R.string.call_notification_voice_text);
		int icon = NotificationDisguise.smallIcon(context,
				R.drawable.ic_phone_white);
		Notification publicVersion =
				new NotificationCompat.Builder(context, RING_CHANNEL_ID)
						.setContentTitle(title)
						.setSmallIcon(icon)
						.setCategory(NotificationCompat.CATEGORY_CALL)
						.build();
		NotificationCompat.Builder builder =
				new NotificationCompat.Builder(context, RING_CHANNEL_ID)
						.setContentTitle(title)
						.setContentText(text)
						.setSmallIcon(icon)
						.setPriority(NotificationCompat.PRIORITY_MAX)
						.setCategory(NotificationCompat.CATEGORY_CALL)
						.setGroup(GROUP)
						.setOngoing(true)
						.setAutoCancel(false)
						.setContentIntent(pendingIntent)
						.setFullScreenIntent(pendingIntent, true)
						.setTimeoutAfter(IncomingCallRinger.RING_TIMEOUT_MS)
						.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
						.setPublicVersion(publicVersion);
		PendingIntent accept = acceptIntent(intent);
		PendingIntent decline = declineIntent(contactId, callId);
		if (callStyle && !disguised && Build.VERSION.SDK_INT >= 31) {
			Person caller = new Person.Builder().setName(title).build();
			builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(
					caller, decline, accept).setIsVideo(video));
		} else {
			builder.addAction(R.drawable.ic_phone_white,
					context.getString(R.string.accept), accept);
			builder.addAction(R.drawable.ic_close,
					context.getString(R.string.decline), decline);
		}
		if (!alert) builder.setSilent(true);
		Notification n = builder.build();
		if (alert) n.flags |= Notification.FLAG_INSISTENT;
		return n;
	}

	void createRingChannel() {
		NotificationManager manager =
				context.getSystemService(NotificationManager.class);
		if (manager == null) return;
		NotificationChannel ring = new NotificationChannel(RING_CHANNEL_ID,
				context.getString(R.string.voice_call_channel_ring),
				NotificationManager.IMPORTANCE_HIGH);
		ring.setDescription(context.getString(
				R.string.voice_call_channel_ring_desc));
		ring.enableVibration(true);
		ring.setVibrationPattern(new long[]{0, 1000, 500, 1000});
		ring.setSound(RingtoneManager.getDefaultUri(
				RingtoneManager.TYPE_RINGTONE),
				new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
						.setContentType(
								AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build());
		ring.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		manager.createNotificationChannel(ring);
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
	}

	void createChannels() {
		NotificationManager manager = context.getSystemService(NotificationManager.class);
		if (manager == null) return;
		createRingChannel();
		NotificationChannel ongoing = new NotificationChannel(
				CHANNEL_ID_ONGOING,
				context.getString(R.string.voice_call_channel_ongoing),
				NotificationManager.IMPORTANCE_LOW);
		ongoing.setDescription(context.getString(
				R.string.voice_call_channel_ongoing_desc));
		ongoing.enableLights(false);
		ongoing.enableVibration(false);
		ongoing.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
		manager.createNotificationChannel(ongoing);
	}
}
