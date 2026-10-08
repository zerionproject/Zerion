package com.professor.zerion.android.conversation.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.professor.zerion.R;

import org.zerionproject.core.api.contact.ContactId;

class CallNotification {

	private static final String CHANNEL_ID = "voice_call_channel";
	private static final String CHANNEL_ID_ONGOING = "voice_call_ongoing_channel";

	static final String GROUP = "zerion.call";

	private final Service service;

	CallNotification(Service service) {
		this.service = service;
	}

	Notification build(ContactId contactId, boolean isIncoming, String callId,
			VoiceCallService.CallState callState, boolean videoActive) {
		Intent intent = new Intent(service, VoiceCallActivity.class);
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contactId.getInt());
		intent.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, isIncoming);
		intent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

		PendingIntent pendingIntent = PendingIntent.getActivity(service, 0,
				intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		boolean ringingIncoming = isIncoming
				&& callState == VoiceCallService.CallState.RINGING;
		String title = service.getString(ringingIncoming
				? R.string.call_notification_incoming_title
				: R.string.call_notification_ongoing_title);
		String text = service.getString(videoActive
				? R.string.call_notification_video_text
				: R.string.call_notification_voice_text);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(service,
				ringingIncoming ? CHANNEL_ID : CHANNEL_ID_ONGOING)
				.setContentTitle(title)
				.setContentText(text)
				.setSmallIcon(R.drawable.ic_phone_white)
				.setPriority(ringingIncoming ? NotificationCompat.PRIORITY_MAX
						: NotificationCompat.PRIORITY_LOW)
				.setCategory(NotificationCompat.CATEGORY_CALL)
				.setGroup(GROUP)
				.setOngoing(true)
				.setAutoCancel(false)
				.setContentIntent(pendingIntent);
		if (ringingIncoming) {
			builder.setFullScreenIntent(pendingIntent, true);
			Intent acceptIntent = new Intent(intent);
			acceptIntent.putExtra(VoiceCallActivity.EXTRA_ANSWER, true);
			PendingIntent acceptPendingIntent = PendingIntent.getActivity(service, 1,
					acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			builder.addAction(R.drawable.ic_phone_white,
					service.getString(R.string.accept), acceptPendingIntent);
			Intent declineIntent = new Intent(service, VoiceCallService.class);
			declineIntent.setAction(CallIntents.ACTION_DECLINE_CALL);
			PendingIntent declinePendingIntent = PendingIntent.getService(service, 2,
					declineIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			builder.addAction(R.drawable.ic_close,
					service.getString(R.string.decline), declinePendingIntent);
			builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
		}

		return builder.build();
	}

	void createChannels() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager = service.getSystemService(NotificationManager.class);
			if (manager == null) return;

			NotificationChannel incoming = new NotificationChannel(
					CHANNEL_ID,
					service.getString(R.string.voice_call_channel_incoming),
					NotificationManager.IMPORTANCE_HIGH);
			incoming.setDescription(service.getString(
					R.string.voice_call_channel_incoming_desc));
			incoming.enableLights(true);
			incoming.enableVibration(true);
			incoming.setVibrationPattern(new long[]{0, 1000, 500, 1000});
			incoming.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			manager.createNotificationChannel(incoming);

			NotificationChannel ongoing = new NotificationChannel(
					CHANNEL_ID_ONGOING,
					service.getString(R.string.voice_call_channel_ongoing),
					NotificationManager.IMPORTANCE_LOW);
			ongoing.setDescription(service.getString(
					R.string.voice_call_channel_ongoing_desc));
			ongoing.enableLights(false);
			ongoing.enableVibration(false);
			ongoing.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			manager.createNotificationChannel(ongoing);
		}
	}
}
