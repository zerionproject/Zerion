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
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.professor.zerion.R;

import org.briarproject.bramble.api.contact.ContactId;


public class VoiceCallActivity extends AppCompatActivity {

	private static final int NETWORK_QUALITY_UPDATE_INTERVAL_MS = 1000;

	public static final String EXTRA_CONTACT_ID = "contact_id";
	public static final String EXTRA_CONTACT_NAME = "contact_name";
	public static final String EXTRA_IS_INCOMING = "is_incoming";
	public static final String EXTRA_CALL_ID = "call_id";
	public static final String EXTRA_CALLER_ADDRESS = "caller_address";

	private static final int REQUEST_AUDIO_PERMISSION = 1;
	private static final int REQUEST_CAMERA_PERMISSION = 2;

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
	private ToneGenerator toneGenerator;

	private TextView muteLabel;
	private TextView speakerLabel;
	private TextView videoLabel;

	private TextureView remoteVideoSurface;
	private TextureView localVideoSurface;
	private View remoteVideoContainer;
	private View localVideoContainer;
	private ImageButton videoButton;
	private ImageButton switchCameraButton;
	private View videoButtonContainer;
	private View switchCameraContainer;
	private View avatarContainer;
	private boolean isVideoActive = false;
	private Surface remoteSurface;
	private Surface localSurface;
	private Runnable onRemoteSurfaceReady;
	private boolean autoVideo = false;
	private boolean pendingVideoAfterPermission = false;
	private int remoteVideoRotation = 270;

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
		int cid = intent.getIntExtra(EXTRA_CONTACT_ID, -1);
		if (cid == -1) {
			finish();
			return;
		}
		contactId = new ContactId(cid);
		contactName = null;
		isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false);
		callId = intent.getStringExtra(EXTRA_CALL_ID);
		String callerAddress = intent.getStringExtra(EXTRA_CALLER_ADDRESS);
		autoVideo = intent.getBooleanExtra("auto_video", false);

		initViews();

		if (!hasAudioPermission()) {
			requestAudioPermission();
		}

		Intent serviceIntent = new Intent(this, VoiceCallService.class);
		serviceIntent.putExtra(EXTRA_CONTACT_ID, contactId.getInt());
		serviceIntent.putExtra(EXTRA_IS_INCOMING, isIncoming);
		serviceIntent.putExtra(EXTRA_CALL_ID, callId);
		serviceIntent.putExtra("auto_video", autoVideo);
		if (callerAddress != null) {
			serviceIntent.putExtra(EXTRA_CALLER_ADDRESS, callerAddress);
		}

		startService(serviceIntent);
		bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

		if (isIncoming) {
			showIncomingCallUI();
		} else {
			showActiveCallUI();
			callStatusText.setText(autoVideo ?
					getString(R.string.voice_call_status_video_connecting_tor)
					: getString(R.string.voice_call_status_connecting_tor));
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

		muteLabel = findViewById(R.id.mute_label);
		speakerLabel = findViewById(R.id.speaker_label);
		videoLabel = findViewById(R.id.video_label);

		remoteVideoSurface = findViewById(R.id.remote_video_surface);
		localVideoSurface = findViewById(R.id.local_video_surface);
		remoteVideoContainer = findViewById(R.id.remote_video_container);
		localVideoContainer = findViewById(R.id.local_video_container);
		videoButton = findViewById(R.id.video_button);
		switchCameraButton = findViewById(R.id.switch_camera_button);
		videoButtonContainer = findViewById(R.id.video_button_container);
		switchCameraContainer = findViewById(R.id.switch_camera_container);
		avatarContainer = findViewById(R.id.avatar_container);

		if (remoteVideoSurface != null) {
			remoteVideoSurface.setSurfaceTextureListener(
					new TextureView.SurfaceTextureListener() {
				@Override
				public void onSurfaceTextureAvailable(
						SurfaceTexture st, int width, int height) {
					remoteSurface = new Surface(st);
					if (onRemoteSurfaceReady != null) {
						Runnable cb = onRemoteSurfaceReady;
						onRemoteSurfaceReady = null;
						cb.run();
					}
				}

				@Override
				public void onSurfaceTextureSizeChanged(
						SurfaceTexture st, int width, int height) {
					if (isVideoActive) applyRemoteVideoTransform();
				}

				@Override
				public boolean onSurfaceTextureDestroyed(
						SurfaceTexture st) {
					remoteSurface = null;
					return true;
				}

				@Override
				public void onSurfaceTextureUpdated(
						SurfaceTexture st) {
				}
			});
		}

		if (localVideoSurface != null) {
			localVideoSurface.setSurfaceTextureListener(
					new TextureView.SurfaceTextureListener() {
				@Override
				public void onSurfaceTextureAvailable(
						SurfaceTexture st, int width, int height) {
					st.setDefaultBufferSize(
							VideoEncoder.WIDTH, VideoEncoder.HEIGHT);
					localSurface = new Surface(st);
					if (isVideoActive && isBound
							&& voiceCallService != null) {
						voiceCallService.updatePreviewSurface(
								localSurface);
						applyLocalVideoTransform();
					}
				}

				@Override
				public void onSurfaceTextureSizeChanged(
						SurfaceTexture st, int width, int height) {
					if (isVideoActive) applyLocalVideoTransform();
				}

				@Override
				public boolean onSurfaceTextureDestroyed(
						SurfaceTexture st) {
					localSurface = null;
					return true;
				}

				@Override
				public void onSurfaceTextureUpdated(
						SurfaceTexture st) {
				}
			});
		}

		endCallButton.setOnClickListener(v -> endCall());
		acceptCallButton.setOnClickListener(v -> acceptCall());
		declineCallButton.setOnClickListener(v -> declineCall());
		muteButton.setOnClickListener(v -> toggleMute());
		speakerButton.setOnClickListener(v -> toggleSpeaker());

		android.content.SharedPreferences prefs =
				com.professor.zerion.android.AppModule.getUiPrefs();
		boolean videoEnabled = prefs != null && prefs.getBoolean(
				com.professor.zerion.android.settings.SecurityFragment
						.PREF_VIDEO_CALLS_ENABLED, false);

		if (videoButton != null) {
			if (videoEnabled) {
				videoButton.setOnClickListener(v -> toggleVideo());
			} else {
				if (videoButtonContainer != null) {
					videoButtonContainer.setVisibility(View.GONE);
				} else {
					videoButton.setVisibility(View.GONE);
				}
			}
		}
		if (switchCameraButton != null) {
			if (videoEnabled) {
				switchCameraButton.setOnClickListener(v -> switchCamera());
			} else if (switchCameraContainer != null) {
				switchCameraContainer.setVisibility(View.GONE);
			}
		}
	}

	private void showIncomingCallUI() {
		incomingCallLayout.setVisibility(View.VISIBLE);
		activeCallLayout.setVisibility(View.GONE);
		callStatusText.setText(autoVideo ?
				"Incoming secure video call..." : "Incoming secure call...");
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
			callStatusText.setText(R.string.call_connecting);
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
		finish();
	}

	private void toggleMute() {
		isMuted = !isMuted;
		if (isBound && voiceCallService != null) {
			voiceCallService.setMuted(isMuted);
		}
		muteButton.setImageResource(isMuted ?
				R.drawable.ic_mic_off_white : R.drawable.ic_mic_white);
		muteButton.setBackgroundResource(isMuted ?
				R.drawable.bg_call_control_button_active :
				R.drawable.bg_call_control_button);
		if (muteLabel != null) {
			muteLabel.setText(isMuted ? "Muted" : "Mute");
		}
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
		speakerButton.setBackgroundResource(isSpeakerOn ?
				R.drawable.bg_call_control_button_active :
				R.drawable.bg_call_control_button);
		if (speakerLabel != null) {
			speakerLabel.setText(isSpeakerOn ? "Speaker On" : "Speaker");
		}
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
			toneGenerator = new ToneGenerator(
					AudioManager.STREAM_VOICE_CALL, 80);
			toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE);
		} catch (Exception e) {
		}
	}

	private void stopDialTone() {
		if (toneGenerator != null) {
			try {
				toneGenerator.stopTone();
				toneGenerator.release();
			} catch (Exception e) {
			}
			toneGenerator = null;
		}
	}

	public void onCallStateChanged(VoiceCallService.CallState state) {
		runOnUiThread(() -> {
			switch (state) {
				case CONNECTING:
					if (voiceCallService != null
							&& voiceCallService.isReconnecting()) {
						callStatusText.setText(R.string.voice_call_status_reconnecting);
					} else {
						callStatusText.setText(R.string.voice_call_status_connecting_tor);
					}
					break;
				case RINGING:
					callStatusText.setText(R.string.voice_call_status_ringing);
					if (!isIncoming) {
						stopRingtone();
						stopDialTone();
						playDialTone();
					}
					break;
				case CONNECTED:
					onCallConnected();
					break;
				case DISCONNECTED:
					onCallDisconnected();
					break;
				case FAILED:
					onCallFailed(getString(
							R.string.voice_call_reason_connection_failed));
					break;
				default:
					break;
			}
		});
	}

	public void onCallConnected() {
		runOnUiThread(() -> {
			stopRingtone();
			stopDialTone();
			callStatusText.setText(R.string.voice_call_status_connected);
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
			callDuration.stop();
			long elapsed = SystemClock.elapsedRealtime() - callDuration.getBase();
			long secs = elapsed / 1000;
			long mins = secs / 60;
			secs = secs % 60;
			String duration = String.format("%d:%02d", mins, secs);
			callStatusText.setText(
					getString(R.string.voice_call_status_ended, duration));
			if (!isFinishing) {
				isFinishing = true;
				handler.postDelayed(this::finish, 1500);
			}
		});
	}

	public void onCallFailed(String reason) {
		runOnUiThread(() -> {
			stopRingtone();
			stopDialTone();
			callStatusText.setText(
					getString(R.string.voice_call_status_failed, reason));
			if (!isFinishing) {
				isFinishing = true;
				handler.postDelayed(this::finish, 2000);
			}
		});
	}

	public void onCallRejectedPeerDisabledCalls() {
		runOnUiThread(() -> {
			stopRingtone();
			stopDialTone();
			String name = contactNameText.getText() != null
					? contactNameText.getText().toString() : "Contact";
			callStatusText.setText(getString(
					R.string.call_rejected_peer_disabled_calls, name)
					+ "\n" + getString(
					R.string.call_rejected_peer_disabled_calls_hint));
			if (!isFinishing) {
				isFinishing = true;
				handler.postDelayed(this::finish, 3500);
			}
		});
	}

	public void onCallRejectedPeerDisabledVideo() {
		runOnUiThread(() -> {
			stopRingtone();
			stopDialTone();
			String name = contactNameText.getText() != null
					? contactNameText.getText().toString() : "Contact";
			callStatusText.setText(getString(
					R.string.call_rejected_peer_disabled_video, name)
					+ "\n" + getString(
					R.string.call_rejected_peer_disabled_video_hint));
			if (!isFinishing) {
				isFinishing = true;
				handler.postDelayed(this::finish, 3500);
			}
		});
	}

	private void updateCallState() {
		if (isBound && voiceCallService != null) {
			VoiceCallService.CallState state = voiceCallService.getCallState();
			switch (state) {
				case CONNECTING:
					if (voiceCallService != null
							&& voiceCallService.isReconnecting()) {
						callStatusText.setText(R.string.voice_call_status_reconnecting);
					} else {
						callStatusText.setText(R.string.voice_call_status_connecting_tor);
					}
					break;
				case RINGING:
					callStatusText.setText(R.string.voice_call_status_ringing);
					if (!isIncoming) {
						stopRingtone();
						stopDialTone();
						playDialTone();
					}
					break;
				case CONNECTED:
					onCallConnected();
					break;
				case DISCONNECTED:
					onCallDisconnected();
					break;
				case FAILED:
					onCallFailed(getString(
							R.string.voice_call_reason_connection_failed));
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
	public void onUserLeaveHint() {
		super.onUserLeaveHint();
		if (isVideoActive && android.os.Build.VERSION.SDK_INT >= 26) {
			try {
				android.app.PictureInPictureParams.Builder pip =
						new android.app.PictureInPictureParams.Builder();
				pip.setAspectRatio(new android.util.Rational(9, 16));
				enterPictureInPictureMode(pip.build());
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	public void onPictureInPictureModeChanged(boolean inPip) {
		super.onPictureInPictureModeChanged(inPip);
		int controlsVisibility = inPip ? View.GONE : View.VISIBLE;
		if (activeCallLayout != null) activeCallLayout.setVisibility(controlsVisibility);
		if (contactNameText != null) contactNameText.setVisibility(controlsVisibility);
		if (callStatusText != null) callStatusText.setVisibility(controlsVisibility);
		if (callDuration != null) callDuration.setVisibility(controlsVisibility);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		handler.removeCallbacksAndMessages(null);
		onRemoteSurfaceReady = null;
		remoteSurface = null;
		localSurface = null;

		if (isBound) {
			if (voiceCallService != null) {
				voiceCallService.clearCallActivity();
			}
			unbindService(serviceConnection);
			isBound = false;
		}
		stopRingtone();
		stopDialTone();
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

	private void toggleVideo() {
		if (!isBound || voiceCallService == null) return;

		if (voiceCallService.isVideoEnabled()) {
			voiceCallService.endVideo();
		} else {
			if (!hasCameraPermission()) {
				requestCameraPermission();
				return;
			}
			Toast.makeText(this, "Setting up video...",
					Toast.LENGTH_SHORT).show();
			if (videoButton != null) {
				videoButton.setBackgroundResource(
						R.drawable.bg_call_control_button_active);
			}
			voiceCallService.resumeVideo();
		}
	}

	private void switchCamera() {
		if (isBound && voiceCallService != null) {
			if (switchCameraButton != null) {
				switchCameraButton.setEnabled(false);
				switchCameraButton.setAlpha(0.4f);
			}
			voiceCallService.switchCamera();
			handler.postDelayed(() -> {
				if (switchCameraButton != null) {
					switchCameraButton.setEnabled(true);
					switchCameraButton.setAlpha(1.0f);
				}
			}, 1500);
		}
	}

	public Surface getRemoteVideoSurface() {
		return remoteSurface;
	}

	public Surface getLocalVideoSurface() {
		return localSurface;
	}

	public void getRemoteVideoSurfaceWhenReady(
			java.util.function.Consumer<Surface> callback) {
		runOnUiThread(() -> {
			if (remoteSurface != null) {
				callback.accept(remoteSurface);
			} else {
				onRemoteSurfaceReady = () -> {
					if (remoteSurface != null) {
						callback.accept(remoteSurface);
					}
				};
			}
		});
	}

	public void onVideoOfferReceived() {
		runOnUiThread(() -> {
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.video_call)
					.setMessage(R.string.video_offer_received)
					.setPositiveButton(R.string.video_offer_accept,
							(dialog, which) -> {
						if (!hasCameraPermission()) {
							requestCameraPermission();
							return;
						}
						if (isBound && voiceCallService != null) {
							voiceCallService.acceptVideoOffer();
						}
					})
					.setNegativeButton(R.string.video_offer_reject,
							(dialog, which) -> {
						if (isBound && voiceCallService != null) {
							voiceCallService.rejectVideoOffer();
						}
					})
					.setCancelable(false)
					.show();
		});
	}

	public void onVideoStarted() {
		runOnUiThread(() -> {
			isVideoActive = true;
			if (isBound && voiceCallService != null) {
				voiceCallService.setVideoRotationCallback(
						degrees -> runOnUiThread(() -> {
							remoteVideoRotation = degrees;
							applyRemoteVideoTransform();
						}));
				voiceCallService.setCameraReadyCallback(
						(orientation, isFront) ->
								runOnUiThread(
										this::applyLocalVideoTransform));
			}
			if (remoteVideoContainer != null) {
				remoteVideoContainer.setVisibility(View.VISIBLE);
				applyRemoteVideoTransform();
			}
			if (localVideoContainer != null) {
				localVideoContainer.setVisibility(View.VISIBLE);
				applyLocalVideoTransform();
			}
			if (avatarContainer != null) {
				avatarContainer.setVisibility(View.GONE);
			}
			if (contactNameText != null) {
				contactNameText.setVisibility(View.GONE);
			}
			if (callStatusText != null) {
				callStatusText.setVisibility(View.GONE);
			}
			if (callDuration != null) {
				callDuration.setVisibility(View.GONE);
			}
			TextView callTypeLabel = findViewById(R.id.call_type_label);
			if (callTypeLabel != null) {
				callTypeLabel.setVisibility(View.GONE);
			}
			if (videoButton != null) {
				videoButton.setImageResource(R.drawable.ic_videocam_off);
				videoButton.setBackgroundResource(
						R.drawable.bg_call_control_button_active);
			}
			if (videoLabel != null) {
				videoLabel.setText(R.string.voice_call_video_label_on);
			}
			if (switchCameraContainer != null) {
				switchCameraContainer.setVisibility(View.VISIBLE);
			}
			if (!isSpeakerOn) {
				toggleSpeaker();
			}
		});
	}

	public void onVideoStopped() {
		runOnUiThread(() -> {
			isVideoActive = false;
			if (remoteVideoContainer != null) {
				remoteVideoContainer.setVisibility(View.GONE);
			}
			if (localVideoContainer != null) {
				localVideoContainer.setVisibility(View.GONE);
			}
			if (avatarContainer != null) {
				avatarContainer.setVisibility(View.VISIBLE);
			}
			if (contactNameText != null) {
				contactNameText.setVisibility(View.VISIBLE);
			}
			if (callStatusText != null) {
				callStatusText.setVisibility(View.VISIBLE);
			}
			if (callDuration != null) {
				callDuration.setVisibility(View.VISIBLE);
			}
			TextView callTypeLabel = findViewById(R.id.call_type_label);
			if (callTypeLabel != null) {
				callTypeLabel.setVisibility(View.VISIBLE);
			}
			if (videoButton != null) {
				videoButton.setImageResource(R.drawable.ic_videocam);
				videoButton.setBackgroundResource(
						R.drawable.bg_call_control_button);
			}
			if (videoLabel != null) {
				videoLabel.setText(R.string.video);
			}
			if (switchCameraContainer != null) {
				switchCameraContainer.setVisibility(View.GONE);
			}
		});
	}

	private void applyVideoTransform(TextureView tv, int rotationDegrees,
			boolean mirror) {
		if (tv == null || tv.getWidth() == 0 || tv.getHeight() == 0) return;

		int viewWidth = tv.getWidth();
		int viewHeight = tv.getHeight();
		float centerX = viewWidth / 2f;
		float centerY = viewHeight / 2f;

		float videoWidth = VideoEncoder.WIDTH;
		float videoHeight = VideoEncoder.HEIGHT;

		boolean rotated = (rotationDegrees % 180 != 0);
		float effectiveW = rotated ? videoHeight : videoWidth;
		float effectiveH = rotated ? videoWidth : videoHeight;

		float scaleX = viewWidth / effectiveW;
		float scaleY = viewHeight / effectiveH;
		float scale = Math.max(scaleX, scaleY);

		Matrix matrix = new Matrix();
		matrix.setScale(effectiveW * scale / viewWidth,
				effectiveH * scale / viewHeight, centerX, centerY);
		matrix.postRotate(rotationDegrees, centerX, centerY);

		if (mirror) {
			matrix.postScale(-1f, 1f, centerX, centerY);
		}

		tv.setTransform(matrix);
	}

	private void applyRemoteVideoTransform() {
		if (remoteVideoSurface == null) return;
		remoteVideoSurface.post(() ->
				applyVideoTransform(remoteVideoSurface,
						remoteVideoRotation, false));
	}

	private void applyLocalVideoTransform() {
		if (localVideoSurface == null || !isBound
				|| voiceCallService == null) return;
		int sensorOrientation =
				voiceCallService.getCameraSensorOrientation();
		boolean isFront = voiceCallService.isCameraFront();
		int displayRotation = 0;
		if (android.os.Build.VERSION.SDK_INT
				>= android.os.Build.VERSION_CODES.R) {
			android.view.Display display = getDisplay();
			if (display != null) {
				displayRotation = display.getRotation() * 90;
			}
		} else {
			displayRotation = getWindowManager()
					.getDefaultDisplay().getRotation() * 90;
		}

		final int rotation = (sensorOrientation - displayRotation + 360) % 360;
		localVideoSurface.post(() ->
				applyVideoTransform(localVideoSurface, rotation, false));
	}

	public void onVideoRejected() {
		runOnUiThread(() -> {
			if (videoButton != null) {
				videoButton.setImageResource(R.drawable.ic_videocam);
				videoButton.setBackgroundResource(
						R.drawable.bg_call_control_button);
			}
			if (videoLabel != null) {
				videoLabel.setText(R.string.video);
			}
			Toast.makeText(this, "Video request declined",
					Toast.LENGTH_SHORT).show();
		});
	}

	public void showVideoError(String message) {
		runOnUiThread(() -> {
			Toast.makeText(this, message, Toast.LENGTH_LONG).show();
		});
	}

	private boolean hasCameraPermission() {
		return ContextCompat.checkSelfPermission(this,
				Manifest.permission.CAMERA)
				== PackageManager.PERMISSION_GRANTED;
	}

	private void requestCameraPermission() {
		ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.CAMERA},
				REQUEST_CAMERA_PERMISSION);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
			String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions,
				grantResults);
		if (requestCode == REQUEST_AUDIO_PERMISSION) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			} else {
				callStatusText.setText(R.string.voice_call_status_mic_permission);
				handler.postDelayed(this::finish, 2000);
			}
		} else if (requestCode == REQUEST_CAMERA_PERMISSION) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (pendingVideoAfterPermission) {
					pendingVideoAfterPermission = false;
					if (isBound && voiceCallService != null) {
						voiceCallService.requestVideoUpgrade();
					}
				} else {
					if (isBound && voiceCallService != null) {
						voiceCallService.resumeVideo();
					}
				}
			} else {
				pendingVideoAfterPermission = false;
			}
		}
	}

	@Override
	public void onBackPressed() {
	}
}