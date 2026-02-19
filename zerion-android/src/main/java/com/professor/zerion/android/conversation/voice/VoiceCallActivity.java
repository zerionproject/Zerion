package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.professor.zerion.R;

import org.briarproject.bramble.api.contact.ContactId;

import javax.inject.Inject;

public class VoiceCallActivity extends AppCompatActivity {

	private static final int NETWORK_QUALITY_UPDATE_INTERVAL_MS = 1000;

	public static final String EXTRA_CONTACT_ID = "contact_id";
	public static final String EXTRA_CONTACT_NAME = "contact_name";
	public static final String EXTRA_IS_INCOMING = "is_incoming";
	public static final String EXTRA_CALL_ID = "call_id";
	public static final String EXTRA_CALLER_ADDRESS = "caller_address";
	public static final String EXTRA_VOICE_CALL_KEY = "voice_call_key";

	private static final int REQUEST_AUDIO_PERMISSION = 1;

	private TextView contactNameText;
	private TextView callStatusText;
	private Chronometer callDuration;
	private ImageView contactAvatar;
	private FloatingActionButton endCallButton;
	private FloatingActionButton acceptCallButton;
	private FloatingActionButton declineCallButton;
	private ImageButton muteButton;
	private ImageButton speakerButton;

	private View incomingCallLayout;
	private View activeCallLayout;

	private LinearLayout networkQualityContainer;
	private ImageView signalStrengthIcon;
	private TextView latencyBadge;
	private TextView packetLossBadge;
	private TextView codecBitrateDisplay;

	private VoiceCallService voiceCallService;
	private boolean isBound = false;
	private boolean isIncoming;
	private ContactId contactId;
	private String contactName;
	private String callId;

	private boolean isMuted = false;
	private boolean isSpeakerOn = false;
	private AudioManager audioManager;
	private Handler handler = new Handler(Looper.getMainLooper());
	private Ringtone ringtone;

	private final Runnable networkQualityUpdateRunnable = new Runnable() {
		@Override
		public void run() {
			updateNetworkQuality();
			handler.postDelayed(this, NETWORK_QUALITY_UPDATE_INTERVAL_MS);
		}
	};

	private ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			VoiceCallService.LocalBinder binder =
					(VoiceCallService.LocalBinder) service;
			voiceCallService = binder.getService();
			isBound = true;
			voiceCallService.setCallActivity(VoiceCallActivity.this);
			updateCallState();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			voiceCallService = null;
			isBound = false;
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_voice_call);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		}
		getWindow().addFlags(
				WindowManager.LayoutParams.FLAG_SECURE |
				WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
				WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
				WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
				WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

		Intent intent = getIntent();
		contactId = new ContactId(intent.getIntExtra(EXTRA_CONTACT_ID, 0));
		contactName = intent.getStringExtra(EXTRA_CONTACT_NAME);
		isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false);
		callId = intent.getStringExtra(EXTRA_CALL_ID);
		String callerAddress = intent.getStringExtra(EXTRA_CALLER_ADDRESS);

		initViews();

		if (!hasAudioPermission()) {
			requestAudioPermission();
		}

		Intent serviceIntent = new Intent(this, VoiceCallService.class);
		serviceIntent.putExtra(EXTRA_CONTACT_ID, contactId.getInt());
		serviceIntent.putExtra(EXTRA_CONTACT_NAME, contactName);
		serviceIntent.putExtra(EXTRA_IS_INCOMING, isIncoming);
		serviceIntent.putExtra(EXTRA_CALL_ID, callId);
		if (callerAddress != null) {
			serviceIntent.putExtra(EXTRA_CALLER_ADDRESS, callerAddress);
		}

		startService(serviceIntent);
		bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

		if (isIncoming) {
			showIncomingCallUI();
		} else {
			showActiveCallUI();
			callStatusText.setText("Connecting via Tor...");
			playDialTone();
		}
	}

	private void initViews() {
		contactNameText = findViewById(R.id.contact_name);
		callStatusText = findViewById(R.id.call_status);
		callDuration = findViewById(R.id.call_duration);
		contactAvatar = findViewById(R.id.contact_avatar);

		incomingCallLayout = findViewById(R.id.incoming_call_layout);
		activeCallLayout = findViewById(R.id.active_call_layout);

		endCallButton = findViewById(R.id.end_call_button);
		acceptCallButton = findViewById(R.id.accept_call_button);
		declineCallButton = findViewById(R.id.decline_call_button);
		muteButton = findViewById(R.id.mute_button);
		speakerButton = findViewById(R.id.speaker_button);

		networkQualityContainer = findViewById(R.id.network_quality_container);
		signalStrengthIcon = findViewById(R.id.signal_strength_icon);
		latencyBadge = findViewById(R.id.latency_badge);
		packetLossBadge = findViewById(R.id.packet_loss_badge);
		codecBitrateDisplay = findViewById(R.id.codec_bitrate_display);

		contactNameText.setText(contactName != null ? contactName : "Unknown");

		endCallButton.setOnClickListener(v -> endCall());
		acceptCallButton.setOnClickListener(v -> acceptCall());
		declineCallButton.setOnClickListener(v -> declineCall());
		muteButton.setOnClickListener(v -> toggleMute());
		speakerButton.setOnClickListener(v -> toggleSpeaker());
	}

	private void showIncomingCallUI() {
		incomingCallLayout.setVisibility(View.VISIBLE);
		activeCallLayout.setVisibility(View.GONE);
		callStatusText.setText("Incoming secure call...");
		playRingtone();
	}

	private void showActiveCallUI() {
		incomingCallLayout.setVisibility(View.GONE);
		activeCallLayout.setVisibility(View.VISIBLE);
		stopRingtone();
	}

	private void acceptCall() {
		showActiveCallUI();
		if (isBound && voiceCallService != null) {
			voiceCallService.acceptCall();
			callStatusText.setText("Connecting...");
		}
	}

	private void declineCall() {
		if (isBound && voiceCallService != null) {
			voiceCallService.declineCall();
		}
		finish();
	}

	private void endCall() {
		if (isBound && voiceCallService != null) {
			try {
				voiceCallService.endCall();
			} catch (Exception e) {
			}
		}
		if (callId != null) {
			Intent cleanupIntent = new Intent("com.professor.zerion.CLEANUP_VOICE_CALL");
			cleanupIntent.putExtra("call_id", callId);
			androidx.localbroadcastmanager.content.LocalBroadcastManager
					.getInstance(this).sendBroadcast(cleanupIntent);
		}
		finish();
	}

	private void toggleMute() {
		isMuted = !isMuted;
		if (isBound && voiceCallService != null) {
			voiceCallService.setMuted(isMuted);
		}
		muteButton.setImageResource(isMuted ?
				R.drawable.ic_mic_off_white : R.drawable.ic_mic_white);
	}

	private void toggleSpeaker() {
		isSpeakerOn = !isSpeakerOn;
		if (isBound && voiceCallService != null) {
			voiceCallService.setSpeakerphoneOn(isSpeakerOn);
		} else {
			audioManager.setSpeakerphoneOn(isSpeakerOn);
		}
		speakerButton.setImageResource(isSpeakerOn ?
				R.drawable.ic_volume_up_white : R.drawable.ic_volume_down_white);
	}

	private void playRingtone() {
		try {
			Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
			ringtone = RingtoneManager.getRingtone(getApplicationContext(), ringtoneUri);
			if (ringtone != null) {
				ringtone.play();
			}
		} catch (Exception e) {
		}
	}

	private void stopRingtone() {
		if (ringtone != null && ringtone.isPlaying()) {
			ringtone.stop();
			ringtone = null;
		}
	}

	private void playDialTone() {
		try {
			Uri dialToneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
			ringtone = RingtoneManager.getRingtone(getApplicationContext(), dialToneUri);
			if (ringtone != null) {
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
					ringtone.setLooping(true);
				}
				ringtone.play();
			}
		} catch (Exception e) {
		}
	}

	public void onCallConnected() {
		runOnUiThread(() -> {
			stopRingtone();
			callStatusText.setText("Connected");
			callDuration.setBase(SystemClock.elapsedRealtime());
			callDuration.start();
			callDuration.setVisibility(View.VISIBLE);

			networkQualityContainer.setVisibility(View.VISIBLE);
			handler.post(networkQualityUpdateRunnable);
		});
	}

	private volatile boolean isFinishing = false;

	public void onCallDisconnected() {
		runOnUiThread(() -> {
			callStatusText.setText("Call ended");
			callDuration.stop();
			if (!isFinishing) {
				isFinishing = true;
				handler.postDelayed(this::finish, 1000);
			}
		});
	}

	public void onCallFailed(String reason) {
		runOnUiThread(() -> {
			stopRingtone();
			callStatusText.setText("Call failed: " + reason);
			if (!isFinishing) {
				isFinishing = true;
				handler.postDelayed(this::finish, 2000);
			}
		});
	}

	private void updateCallState() {
		if (isBound && voiceCallService != null) {
			VoiceCallService.CallState state = voiceCallService.getCallState();
			switch (state) {
				case CONNECTING:
					callStatusText.setText("Connecting via Tor...");
					break;
				case RINGING:
					callStatusText.setText("Ringing...");
					if (!isIncoming) {
						stopRingtone();
						playRingtone();
					}
					break;
				case CONNECTED:
					onCallConnected();
					break;
				case DISCONNECTED:
					onCallDisconnected();
					break;
				case FAILED:
					onCallFailed("Connection failed");
					break;
			}
		}
	}

	private boolean hasAudioPermission() {
		return ContextCompat.checkSelfPermission(this,
				Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
	}

	private void requestAudioPermission() {
		ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.RECORD_AUDIO},
				REQUEST_AUDIO_PERMISSION);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions,
			int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_AUDIO_PERMISSION) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			} else {
				callStatusText.setText("Microphone permission required");
				handler.postDelayed(this::finish, 2000);
			}
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		handler.removeCallbacksAndMessages(null);

		if (isBound) {
			if (voiceCallService != null) {
				voiceCallService.clearCallActivity();
			}
			unbindService(serviceConnection);
			isBound = false;
		}
		stopRingtone();
	}

	private void updateNetworkQuality() {
		if (!isBound || voiceCallService == null) {
			return;
		}

		NetworkMetrics metrics = voiceCallService.getNetworkMetrics();
		if (metrics == null) {
			return;
		}

		runOnUiThread(() -> {
			long latency = metrics.getLatencyMs();
			latencyBadge.setText(latency + "ms");

			int latencyColor;
			if (latency < 150) {
				latencyColor = ContextCompat.getColor(this, R.color.zerion_success_green);
			} else if (latency < 300) {
				latencyColor = ContextCompat.getColor(this, R.color.zerion_warning);
			} else {
				latencyColor = ContextCompat.getColor(this, R.color.zerion_error_red);
			}
			latencyBadge.setTextColor(latencyColor);

			double packetLoss = metrics.getPacketLossPercentage();
			if (packetLoss > 1.0) {
				packetLossBadge.setText(String.format("Loss: %.1f%%", packetLoss));
				packetLossBadge.setVisibility(View.VISIBLE);

				int lossColor = packetLoss < 5.0 ?
						ContextCompat.getColor(this, R.color.zerion_warning) :
						ContextCompat.getColor(this, R.color.zerion_error_red);
				packetLossBadge.setTextColor(lossColor);
			} else {
				packetLossBadge.setVisibility(View.GONE);
			}

			int signalQuality = metrics.getSignalQuality();
			int signalIcon;
			int signalColor;

			switch (signalQuality) {
				case NetworkMetrics.SIGNAL_EXCELLENT:
					signalIcon = R.drawable.ic_signal_5;
					signalColor = ContextCompat.getColor(this, R.color.zerion_success_green);
					break;
				case NetworkMetrics.SIGNAL_GOOD:
					signalIcon = R.drawable.ic_signal_4;
					signalColor = ContextCompat.getColor(this, R.color.zerion_success_green);
					break;
				case NetworkMetrics.SIGNAL_FAIR:
					signalIcon = R.drawable.ic_signal_3;
					signalColor = ContextCompat.getColor(this, R.color.zerion_warning);
					break;
				case NetworkMetrics.SIGNAL_POOR:
					signalIcon = R.drawable.ic_signal_2;
					signalColor = ContextCompat.getColor(this, R.color.zerion_warning);
					break;
				default:
					signalIcon = R.drawable.ic_signal_1;
					signalColor = ContextCompat.getColor(this, R.color.zerion_error_red);
					break;
			}

			signalStrengthIcon.setImageResource(signalIcon);
			signalStrengthIcon.setColorFilter(signalColor);

			codecBitrateDisplay.setText(metrics.getCodecBitrateDisplay());
		});
	}

	@Override
	public void onBackPressed() {
	}
}