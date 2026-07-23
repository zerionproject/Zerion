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
		String title = ringingIncoming ? "Incoming call" : "Ongoing call";
		String text = videoActive ? "Secure video call" : "Secure voice call";

		NotificationCompat.Builder builder = new NotificationCompat.Builder(service,
				ringingIncoming ? CHANNEL_ID : CHANNEL_ID_ONGOING)
				.setContentTitle(title)
				.setContentText(text)
				.setSmallIcon(R.drawable.ic_phone_white)
				.setPriority(ringingIncoming ? NotificationCompat.PRIORITY_MAX
						: NotificationCompat.PRIORITY_LOW)
				.setCategory(NotificationCompat.CATEGORY_CALL)
				.setOngoing(true)
				.setAutoCancel(false)
				.setContentIntent(pendingIntent);
		if (ringingIncoming) {
			builder.setFullScreenIntent(pendingIntent, true);
			Intent acceptIntent = new Intent(service, VoiceCallService.class);
			acceptIntent.setAction(CallIntents.ACTION_ACCEPT_CALL);
			PendingIntent acceptPendingIntent = PendingIntent.getService(service, 1,
					acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			builder.addAction(R.drawable.ic_phone_white, "Accept", acceptPendingIntent);
			Intent declineIntent = new Intent(service, VoiceCallService.class);
			declineIntent.setAction(CallIntents.ACTION_DECLINE_CALL);
			PendingIntent declinePendingIntent = PendingIntent.getService(service, 2,
					declineIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			builder.addAction(R.drawable.ic_close, "Decline", declinePendingIntent);
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
					"Incoming Calls",
					NotificationManager.IMPORTANCE_HIGH);
			incoming.setDescription("Ringing alerts for incoming voice calls");
			incoming.enableLights(true);
			incoming.enableVibration(true);
			incoming.setVibrationPattern(new long[]{0, 1000, 500, 1000});
			incoming.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			manager.createNotificationChannel(incoming);

			NotificationChannel ongoing = new NotificationChannel(
					CHANNEL_ID_ONGOING,
					"Ongoing Calls",
					NotificationManager.IMPORTANCE_LOW);
			ongoing.setDescription("Status of a voice call in progress");
			ongoing.enableLights(false);
			ongoing.enableVibration(false);
			ongoing.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			manager.createNotificationChannel(ongoing);
		}
	}
}
