package com.professor.zerion.android.conversation.voice;


import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.professor.zerion.R;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.VoiceSignalFactory;
import org.briarproject.briar.api.messaging.VoiceSignalHeader;
import org.briarproject.briar.api.messaging.VoiceSignalType;
import org.briarproject.briar.api.messaging.event.PrivateMessageReceivedEvent;
import org.briarproject.briar.api.messaging.event.VoiceSignalReceivedEvent;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.conversation.voice.VoiceCallConnectionManager;
import org.briarproject.briar.conversation.voice.VoiceCallConnectionHandler;
import org.briarproject.briar.conversation.voice.VoiceCallCrypto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Queue;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AutomaticGainControl;


import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.ZerionApplication;

public class VoiceCallService extends Service implements EventListener {

	private static final String CHANNEL_ID = "voice_call_channel";
	private static final int NOTIFICATION_ID = 1001;

	private static final int SAMPLE_RATE = 16000;

	private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

	private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

	private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
			SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

	private static final boolean USE_OPUS_CODEC = false;
	private static final int OPUS_BITRATE = 24000;
	private static final int OPUS_FRAME_DURATION_MS = 20;

	private static final int PADDED_FRAME_SIZE = 512;

	private static final int AUDIO_SYNC_MARKER = 0x5A455249;

	private static final int AUDIO_READY_MARKER = -1;

	private static final int AUDIO_HEARTBEAT_MARKER = -2;

	public enum CallState {
		IDLE,
		CONNECTING,
		RINGING,
		CONNECTED,
		DISCONNECTED,
		FAILED
	}

	private final IBinder binder = new LocalBinder();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Object streamLock = new Object();
	private final Object torConnectionLock = new Object();
	private volatile boolean isShuttingDown = false;
	private ExecutorService executorService = Executors.newCachedThreadPool();

	private ContactId contactId;
	private String contactName;
	private String callId;
	private boolean isIncoming;
	private volatile CallState callState = CallState.IDLE;
	private volatile VoiceCallActivity callActivity;
	private volatile boolean eventListenerRegistered = false;
	private Runnable callSetupTimeoutRunnable;

	private AudioRecord audioRecord;
	private AudioTrack audioTrack;
	private volatile boolean isRecording = false;
	private volatile boolean isMuted = false;
	private volatile boolean isSpeakerOn = false;
	private AudioManager audioManager;
	private PowerManager.WakeLock proximityWakeLock;

	private OpusEncoder opusEncoder;
	private OpusDecoder opusDecoder;

	private AcousticEchoCanceler echoCanceler;
	private NoiseSuppressor noiseSuppressor;
	private AutomaticGainControl gainControl;

	private NetworkMetrics networkMetrics = new NetworkMetrics();

	private static final int JITTER_BUFFER_CAPACITY = SAMPLE_RATE * 2 * 2;
	private final byte[] sharedJitterBuffer = new byte[JITTER_BUFFER_CAPACITY];
	private volatile int jbWritePos = 0;
	private volatile int jbReadPos = 0;
	private volatile int jbBufferedBytes = 0;
	private final Object jbLock = new Object();
	private volatile boolean playoutStarted = false;

	private Socket audioSocket;
	private ServerSocket serverSocket;
	private DuplexTransportConnection torConnection;
	private String onionAddress;
	private int onionPort;

	private final AtomicLong sendSequenceNumber = new AtomicLong(0);
	private long expectedReceiveSequence = 0;

	private static final int MAX_RECONNECT_ATTEMPTS = 3;
	private volatile int reconnectAttempts = 0;
	private volatile boolean isReconnecting = false;
	private String lastRemoteOnion;
	private int lastRemotePort;

	private long callStartTime = 0;
	private long totalBytesSent = 0;
	private long totalBytesReceived = 0;
	private long totalFramesSent = 0;
	private long totalFramesReceived = 0;
	private long totalPacketLoss = 0;

	private Group conversationGroup;

	private String callerOnionAddress;
	private int callerOnionPort;

	private ContactManager contactManager;
	private MessagingManager messagingManager;
	private VoiceSignalFactory voiceSignalFactory;
	private EventBus eventBus;
	private PluginManager pluginManager;
	private Executor dbExecutor;
	private Executor ioExecutor;
	private VoiceCallConnectionManager connectionManager;
	private VoiceCallCrypto voiceCallCrypto;
	private SecretKey voiceCallKey;
	private VoiceCallCrypto.AudioKeys audioKeys;

	private byte[] localEphemeralSecret;
	private byte[] remoteEphemeralSecret;

	public class LocalBinder extends Binder {
		public VoiceCallService getService() {
			return VoiceCallService.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		createNotificationChannel();

		ZerionApplication app = (ZerionApplication) getApplication();
		AndroidComponent component = app.getApplicationComponent();

		contactManager = component.contactManager();
		messagingManager = component.messagingManager();
		voiceSignalFactory = component.voiceSignalFactory();
		eventBus = component.eventBus();
		pluginManager = component.pluginManager();
		dbExecutor = component.databaseExecutor();
		ioExecutor = component.ioExecutor();
		connectionManager = component.voiceCallConnectionManager();
		voiceCallCrypto = component.voiceCallCrypto();

		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

		PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		if (powerManager != null) {
			proximityWakeLock = powerManager.newWakeLock(
					PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
					"zerion:voice_call_proximity");
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();

			if ("ACTION_ACCEPT_CALL".equals(action)) {
				acceptCall();
				launchCallActivity();
				return START_NOT_STICKY;
			} else if ("ACTION_DECLINE_CALL".equals(action)) {
				declineCall();
				return START_NOT_STICKY;
			}

			if ("com.professor.zerion.VOICE_CALL_SIGNALING".equals(action)) {
				String signalingMessage = intent.getStringExtra("signaling_message");
				if (signalingMessage != null) {
					handleIncomingSignaling(signalingMessage);
				}
				return START_NOT_STICKY;
			}

			contactId = new ContactId(intent.getIntExtra(
					VoiceCallActivity.EXTRA_CONTACT_ID, 0));
			contactName = intent.getStringExtra(VoiceCallActivity.EXTRA_CONTACT_NAME);
			isIncoming = intent.getBooleanExtra(
					VoiceCallActivity.EXTRA_IS_INCOMING, false);
			callId = intent.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID);

			if (isIncoming) {
				SecretKey heldKey = VoiceCallKeyHolder.consumeKey();
				if (heldKey != null) {
					voiceCallKey = heldKey;
				}
				byte[] heldEphemeral = VoiceCallKeyHolder.consumeRemoteEphemeral();
				if (heldEphemeral != null) {
					remoteEphemeralSecret = heldEphemeral;
				}
			}

			if (callId == null) {
				callId = UUID.randomUUID().toString();
			}

			if (!eventListenerRegistered) {
				eventBus.addListener(this);
				eventListenerRegistered = true;
			}

			dbExecutor.execute(() -> {
				try {
					Contact contact = contactManager.getContact(contactId);
					conversationGroup = messagingManager.getContactGroup(contact);
				} catch (DbException e) {
				}
			});

			startForegroundWithPermissionCheck();

			if (!isIncoming) {
				initiateCall();
			} else {
				callState = CallState.RINGING;
			}
		}

		return START_NOT_STICKY;
	}

	private void startForegroundWithPermissionCheck() {
		Notification notification = createNotification();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			boolean hasMicPermission = ContextCompat.checkSelfPermission(this,
					Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

			if (hasMicPermission) {
				startForeground(NOTIFICATION_ID, notification,
						ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
			} else {
				startForeground(NOTIFICATION_ID, notification,
						ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			}
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(NOTIFICATION_ID, notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
		} else {
			startForeground(NOTIFICATION_ID, notification);
		}
	}

	private void initiateCall() {
		callState = CallState.CONNECTING;
		updateCallActivity();

		executorService.execute(() -> {
			try {
				if (voiceCallKey == null) {
					voiceCallKey = voiceCallCrypto.generateVoiceCallKey();
				}

	
				localEphemeralSecret = voiceCallCrypto.generateEphemeralSecret();

				sendCallOffer();

				callState = CallState.RINGING;
				updateCallActivity();


				scheduleCallSetupTimeout(90_000);

			} catch (Exception e) {
				callState = CallState.FAILED;
				updateCallActivity();
			}
		});
	}

	public void acceptCall() {
		if (callState != CallState.RINGING || !isIncoming) return;

		callState = CallState.CONNECTING;
		updateCallActivity();

		executorService.execute(() -> {
			try {
	
				localEphemeralSecret = voiceCallCrypto.generateEphemeralSecret();

				createHiddenService();

				sendCallAnswer();

				scheduleCallSetupTimeout(120_000);

			} catch (Exception e) {
				callState = CallState.FAILED;
				updateCallActivity();
			}
		});
	}

	public void declineCall() {
		if (!isIncoming) return;
		cancelCallSetupTimeout();

		executorService.execute(() -> {
			try {
				sendCallReject();
			} catch (Exception e) {
			}
			if (callId != null) {
				connectionManager.closeEndpoint(callId);
			}
			stopSelf();
		});
	}

	public void endCall() {
		cancelCallSetupTimeout();
		synchronized (streamLock) {
			if (callState == CallState.DISCONNECTED) {
				return;
			}
			callState = CallState.DISCONNECTED;
		}

		executorService.execute(() -> {
			try {
				sendCallEnd();
			} catch (Exception e) {
			}

			try {
				stopAudioStreaming();
			} catch (Exception e) {
			}

			zeroizeKeyMaterial();

			try {
				if (callId != null) {
					connectionManager.closeEndpoint(callId);
				}
			} catch (Exception e) {
			}

			updateCallActivity();
			stopSelf();
		});
	}

	private void createHiddenService() throws IOException {
		try {
			if (!isIncoming && voiceCallKey == null) {
				voiceCallKey = voiceCallCrypto.generateVoiceCallKey();
			}

			if (voiceCallKey == null) {
				throw new IOException("No voice call key available");
			}

			boolean alice = !isIncoming;

			VoiceCallConnectionHandler handler = new VoiceCallConnectionHandler() {
				@Override
				public void handleConnection(DuplexTransportConnection conn) {
					torConnection = conn;

					if (callState == CallState.CONNECTING || callState == CallState.RINGING) {
						cancelCallSetupTimeout();
						callState = CallState.CONNECTED;
						callStartTime = System.currentTimeMillis();
						updateCallActivity();
						startAudioStreaming();
					}
				}
			};

			VoiceCallConnectionManager.EndpointInfo endpoint =
					connectionManager.createIncomingEndpoint(
							callId, voiceCallKey, alice, handler);

			onionAddress = endpoint.onionAddress;
			onionPort = endpoint.port;

		} catch (IOException e) {
			String errorMessage = "Failed to set up secure connection";
			if (e.getMessage() != null) {
				if (e.getMessage().contains("Tor plugin not available")) {
					errorMessage = "Tor network unavailable. Please check your connection.";
				} else if (e.getMessage().contains("No voice call key")) {
					errorMessage = "Security key missing. Please try again.";
				}
			}

			callState = CallState.FAILED;
			updateCallActivity();

			throw new IOException(errorMessage, e);
		}
	}


	private static final java.util.regex.Pattern ONION_V3_PATTERN =
			java.util.regex.Pattern.compile("^[a-z2-7]{56}(\\.onion)?$");

	private void connectToRemoteOnion(String remoteOnion, int remotePort) {
		if (remoteOnion == null || !ONION_V3_PATTERN.matcher(
				remoteOnion.toLowerCase()).matches()) {
			callState = CallState.FAILED;
			updateCallActivity();
			return;
		}
		if (remotePort < 1 || remotePort > 65535) {
			callState = CallState.FAILED;
			updateCallActivity();
			return;
		}

		lastRemoteOnion = remoteOnion;
		lastRemotePort = remotePort;

		executorService.execute(() -> {
			try {
				if (voiceCallKey == null) {
					callState = CallState.FAILED;
					updateCallActivity();
					return;
				}

				boolean alice = !isIncoming;

				torConnection = connectionManager.connectToRemote(
						callId, remoteOnion, voiceCallKey, alice);

				if (torConnection == null) {
					throw new IOException("Failed to connect to remote peer");
				}

				cancelCallSetupTimeout();
				reconnectAttempts = 0;
				isReconnecting = false;
				callState = CallState.CONNECTED;
				if (callStartTime == 0) {
					callStartTime = System.currentTimeMillis();
				}
				updateCallActivity();
				startAudioStreaming();

			} catch (Exception e) {
				String errorMessage = "Failed to connect to contact";
				if (e.getMessage() != null) {
					if (e.getMessage().contains("No voice call key")) {
						errorMessage = "Security key missing. Please try again.";
					} else if (e.getMessage().contains("Tor plugin not available")) {
						errorMessage = "Tor network unavailable. Please check your connection.";
					} else if (e.getMessage().contains("timeout") || e.getMessage().contains("timed out")) {
						errorMessage = "Connection timeout. Contact may be offline.";
					}
				}
				callState = CallState.FAILED;
				updateCallActivity();
			}
		});
	}

	private void deriveAudioEncryptionKeys() {
		if (voiceCallKey == null) {
			throw new IllegalStateException("No voice call key available");
		}

		boolean alice = !isIncoming;

		if (localEphemeralSecret != null && remoteEphemeralSecret != null) {
			audioKeys = voiceCallCrypto.deriveEphemeralAudioKeys(
					voiceCallKey, localEphemeralSecret,
					remoteEphemeralSecret, alice);
			Arrays.fill(localEphemeralSecret, (byte) 0);
			Arrays.fill(remoteEphemeralSecret, (byte) 0);
			localEphemeralSecret = null;
			remoteEphemeralSecret = null;
		} else {
			audioKeys = voiceCallCrypto.deriveAudioKeys(voiceCallKey, alice);
		}
	}

	private void startAudioStreaming() {
		if (isRecording) {
			return;
		}

		deriveAudioEncryptionKeys();

		isRecording = true;

		audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
		audioManager.setSpeakerphoneOn(false);
		isSpeakerOn = false;

		int audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;

		audioTrack = new AudioTrack.Builder()
				.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build())
				.setAudioFormat(new AudioFormat.Builder()
						.setEncoding(AUDIO_FORMAT)
						.setSampleRate(SAMPLE_RATE)
						.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
						.build())
				.setBufferSizeInBytes(BUFFER_SIZE * 4)
				.build();

		if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
			stopAudioStreaming();
			return;
		}

		audioSessionId = audioTrack.getAudioSessionId();

		audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
				SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
				BUFFER_SIZE * 2);

		if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
			stopAudioStreaming();
			return;
		}

		enableAudioProcessing(audioRecord.getAudioSessionId());

		audioRecord.startRecording();
		audioTrack.play();

		if (proximityWakeLock != null && !proximityWakeLock.isHeld()) {
			proximityWakeLock.acquire();
		}

		if (USE_OPUS_CODEC) {
			try {
				opusEncoder = new OpusEncoder(SAMPLE_RATE, 1, OPUS_BITRATE);

				opusDecoder = new OpusDecoder(SAMPLE_RATE, 1);
			} catch (Exception e) {
				opusEncoder = null;
				opusDecoder = null;
			}
		}

		networkMetrics.reset();
		if (USE_OPUS_CODEC && opusEncoder != null) {
			networkMetrics.setCodecInfo("Opus", OPUS_BITRATE / 1000);
		} else {
			networkMetrics.setCodecInfo("PCM", 256);
		}

		synchronized (jbLock) {
			jbWritePos = 0;
			jbReadPos = 0;
			jbBufferedBytes = 0;
			playoutStarted = false;
		}

		startHeartbeat();
		startPlayoutThread();

		final int MAX_ENCRYPTED_FRAME_SIZE = (BUFFER_SIZE * 8) + 64;

		executorService.execute(() -> {
			final int frameSize;
			if (USE_OPUS_CODEC && opusEncoder != null) {
				frameSize = (SAMPLE_RATE / 1000) * OPUS_FRAME_DURATION_MS * 2;
			} else {
				frameSize = BUFFER_SIZE * 4;
			}

			byte[] readBuffer = new byte[frameSize];
			int readOffset = 0;

			try {
				OutputStream outputStream = null;
				for (int retry = 0; retry < 50; retry++) {
					if (torConnection != null) {
						outputStream = torConnection.getWriter().getOutputStream();
						break;
					} else if (audioSocket != null) {
						outputStream = audioSocket.getOutputStream();
						break;
					}
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting for connection");
					}
				}

				if (outputStream == null) {
					throw new IOException("No connection available after 5 second wait");
				}

				DataOutputStream dataOut = new DataOutputStream(
					new BufferedOutputStream(outputStream, frameSize * 2));

				dataOut.writeInt(AUDIO_SYNC_MARKER);
				dataOut.flush();

				dataOut.writeInt(AUDIO_READY_MARKER);
				dataOut.flush();

				while (!isShuttingDown && isRecording && (torConnection != null || (audioSocket != null && audioSocket.isConnected()))) {
					int read = audioRecord.read(readBuffer, readOffset, frameSize - readOffset);

					if (read > 0) {
						readOffset += read;

						if (readOffset >= frameSize) {
							byte[] audioData;

							if (isMuted) {
								audioData = new byte[frameSize];
								Arrays.fill(audioData, (byte) 0);
							} else {
								audioData = Arrays.copyOf(readBuffer, frameSize);
							}

							byte[] encodedAudio;
							if (USE_OPUS_CODEC && opusEncoder != null) {
								try {
									ByteBuffer pcmBuffer = ByteBuffer.wrap(audioData);
									int sampleCount = audioData.length / 2;
									encodedAudio = opusEncoder.encode(pcmBuffer, sampleCount);
									if (encodedAudio.length == 0) {
										readOffset = 0;
										continue;
									}
								} catch (Exception e) {
									readOffset = 0;
									continue;
								}
							} else {
								encodedAudio = audioData;
							}
							VoiceCallCrypto.AudioKeys keys = audioKeys;
							if (keys == null || keys.txKey == null) {
								readOffset = 0;
								continue;
							}
							byte[] encryptedAudio = voiceCallCrypto.encryptAudioFrame(
									encodedAudio, keys.txKey);

							java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
							crc32.update(encryptedAudio, 0, encryptedAudio.length);
							long checksum = crc32.getValue();

							dataOut.writeInt(encryptedAudio.length);
							dataOut.writeLong(sendSequenceNumber.getAndIncrement());
							dataOut.writeLong(System.currentTimeMillis());
							dataOut.writeLong(checksum);
							dataOut.write(encryptedAudio);
							dataOut.flush();

							totalBytesSent += encryptedAudio.length + 28;
							totalFramesSent++;
							networkMetrics.recordPacketSent();

							readOffset = 0;
						}
					}
				}
			} catch (IOException e) {
				handleConnectionError();
			}
		});

		executorService.execute(() -> {
			try {
				InputStream inputStream = null;
				for (int retry = 0; retry < 50; retry++) {
					if (torConnection != null) {
						inputStream = torConnection.getReader().getInputStream();
						break;
					} else if (audioSocket != null) {
						inputStream = audioSocket.getInputStream();
						break;
					}
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting for connection");
					}
				}

				if (inputStream == null) {
					throw new IOException("No connection available after 5 second wait");
				}

				DataInputStream dataIn = new DataInputStream(
					new BufferedInputStream(inputStream, MAX_ENCRYPTED_FRAME_SIZE * 3));

				try {
					int syncMarker = dataIn.readInt();
				} catch (IOException e) {
					return;
				}

				final int BYTES_PER_MS = (SAMPLE_RATE * 2) / 1000;
				final int MIN_BUFFER_FLOOR = BYTES_PER_MS * 60;

				int corruptedFrames = 0;
				int receivedFrames = 0;
				long lastSequence = -1;
				long lastReceiveTime = System.currentTimeMillis();
				final int READ_TIMEOUT_MS = 30000;

				while (!isShuttingDown && isRecording && (torConnection != null || (audioSocket != null && audioSocket.isConnected()))) {
					try {
						if (System.currentTimeMillis() - lastReceiveTime > READ_TIMEOUT_MS) {
							break;
						}

						int encFrameSize = dataIn.readInt();

						if (encFrameSize == AUDIO_READY_MARKER) {
							lastReceiveTime = System.currentTimeMillis();
							continue;
						} else if (encFrameSize == AUDIO_HEARTBEAT_MARKER) {
							lastReceiveTime = System.currentTimeMillis();
							continue;
						} else if (encFrameSize < 0) {
							continue;
						}

						long sequence = dataIn.readLong();
						long timestamp = dataIn.readLong();
						long receivedChecksum = dataIn.readLong();

						lastReceiveTime = System.currentTimeMillis();

						if (encFrameSize > MAX_ENCRYPTED_FRAME_SIZE) {
							continue;
						}

						byte[] encryptedFrame = new byte[encFrameSize];
						dataIn.readFully(encryptedFrame);
						receivedFrames++;

						totalBytesReceived += encFrameSize + 28;
						totalFramesReceived++;
						networkMetrics.recordPacketReceived(sequence);

						long currentTime = System.currentTimeMillis();
						long latency = currentTime - timestamp;
						if (latency > 0 && latency < 10000) {
							networkMetrics.recordLatency(latency);
						}

						java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
						crc32.update(encryptedFrame, 0, encFrameSize);
						long calculatedChecksum = crc32.getValue();

						if (calculatedChecksum != receivedChecksum) {
							corruptedFrames++;
							networkMetrics.recordCorruptedPacket();
							continue;
						}

						VoiceCallCrypto.AudioKeys keys = audioKeys;
						if (keys == null || keys.rxKey == null) {
							continue;
						}

						byte[] encodedAudio;
						try {
							encodedAudio = voiceCallCrypto.decryptAudioFrame(
									encryptedFrame, keys.rxKey);
						} catch (RuntimeException e) {
							corruptedFrames++;
							networkMetrics.recordCorruptedPacket();
							continue;
						}

						byte[] decoded;
						if (USE_OPUS_CODEC && opusDecoder != null) {
							try {
								decoded = opusDecoder.decode(encodedAudio);
							} catch (Exception e) {
								corruptedFrames++;
								networkMetrics.recordCorruptedPacket();
								continue;
							}
						} else {
							decoded = encodedAudio;
						}

						if (lastSequence >= 0) {
							if (sequence <= lastSequence) {
								networkMetrics.recordCorruptedPacket();
								continue;
							}
							if (sequence > lastSequence + 1) {
								long lostFrames = sequence - lastSequence - 1;
								totalPacketLoss += lostFrames;
							}
						}
						lastSequence = sequence;

						if (decoded.length == 0) {
							continue;
						}

						synchronized (jbLock) {
							writeToJitterBuffer(sharedJitterBuffer, decoded,
									jbWritePos, JITTER_BUFFER_CAPACITY);
							jbWritePos = (jbWritePos + decoded.length) % JITTER_BUFFER_CAPACITY;
							jbBufferedBytes = Math.min(jbBufferedBytes + decoded.length,
									JITTER_BUFFER_CAPACITY);

							if (!playoutStarted && jbBufferedBytes >= MIN_BUFFER_FLOOR) {
								playoutStarted = true;
							}
						}

					} catch (IOException e) {
						break;
					}
				}
			} catch (EOFException | SocketException e) {
			} catch (IOException e) {
				if (callState == CallState.CONNECTED) {
					handleConnectionError();
				}
			}
		});
	}

	private void enableAudioProcessing(int audioSessionId) {
		if (AcousticEchoCanceler.isAvailable()) {
			echoCanceler = AcousticEchoCanceler.create(audioSessionId);
			if (echoCanceler != null) {
				echoCanceler.setEnabled(true);
			}
		}
		if (NoiseSuppressor.isAvailable()) {
			noiseSuppressor = NoiseSuppressor.create(audioSessionId);
			if (noiseSuppressor != null) {
				noiseSuppressor.setEnabled(true);
			}
		}
		if (AutomaticGainControl.isAvailable()) {
			gainControl = AutomaticGainControl.create(audioSessionId);
			if (gainControl != null) {
				gainControl.setEnabled(true);
			}
		}
	}

	private void releaseAudioProcessing() {
		if (echoCanceler != null) {
			echoCanceler.setEnabled(false);
			echoCanceler.release();
			echoCanceler = null;
		}
		if (noiseSuppressor != null) {
			noiseSuppressor.setEnabled(false);
			noiseSuppressor.release();
			noiseSuppressor = null;
		}
		if (gainControl != null) {
			gainControl.setEnabled(false);
			gainControl.release();
			gainControl = null;
		}
	}

	private void startHeartbeat() {
		executorService.execute(() -> {
			try {
				while (!isShuttingDown && isRecording && (torConnection != null || (audioSocket != null && audioSocket.isConnected()))) {
					Thread.sleep(30000);

					if (torConnection != null) {
						try {
							DataOutputStream dataOut = new DataOutputStream(
									torConnection.getWriter().getOutputStream());

							dataOut.writeInt(AUDIO_HEARTBEAT_MARKER);
							dataOut.flush();
						} catch (IOException e) {
						}
					} else if (audioSocket != null && audioSocket.isConnected()) {
						DataOutputStream dataOut = new DataOutputStream(audioSocket.getOutputStream());
						dataOut.writeInt(AUDIO_HEARTBEAT_MARKER);
						dataOut.flush();
					}
				}
			} catch (Exception e) {
			}
		});
	}

	private void startPlayoutThread() {
		executorService.execute(() -> {
			final int PCM_FRAME = (SAMPLE_RATE / 1000) * OPUS_FRAME_DURATION_MS * 2;
			final byte[] playBuffer = new byte[PCM_FRAME];

			while (!isShuttingDown && isRecording) {
				if (!playoutStarted) {
					try { Thread.sleep(5); } catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					continue;
				}

				boolean hasFrame;
				synchronized (jbLock) {
					hasFrame = jbBufferedBytes >= PCM_FRAME;
					if (hasFrame) {
						for (int i = 0; i < PCM_FRAME; i++) {
							playBuffer[i] = sharedJitterBuffer[jbReadPos];
							jbReadPos = (jbReadPos + 1) % JITTER_BUFFER_CAPACITY;
						}
						jbBufferedBytes -= PCM_FRAME;
					}
				}

				if (hasFrame) {
					AudioTrack track = audioTrack;
					if (track != null) {
						int written = track.write(playBuffer, 0, PCM_FRAME);
						if (written < 0) {
							networkMetrics.recordWriteError();
						}
					}
				} else {
					networkMetrics.recordUnderrun();
					try { Thread.sleep(5); } catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		});
	}

	private void scheduleCallSetupTimeout(long timeoutMs) {
		cancelCallSetupTimeout();
		callSetupTimeoutRunnable = () -> {
			if (callState == CallState.RINGING ||
					callState == CallState.CONNECTING) {
				callState = CallState.FAILED;
				updateCallActivity();
				if (callActivity != null) {
					callActivity.onCallFailed("Connection timeout");
				}
			}
		};
		mainHandler.postDelayed(callSetupTimeoutRunnable, timeoutMs);
	}

	private void cancelCallSetupTimeout() {
		if (callSetupTimeoutRunnable != null) {
			mainHandler.removeCallbacks(callSetupTimeoutRunnable);
			callSetupTimeoutRunnable = null;
		}
	}

	private void handleConnectionError() {
		if (callState != CallState.CONNECTED && callState != CallState.CONNECTING) {
			return;
		}

		if (isReconnecting) return;
		SecretKey reconnectKey = voiceCallKey;
		if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && reconnectKey != null) {
			isReconnecting = true;
			reconnectAttempts++;
			sendSequenceNumber.addAndGet(1000);
			callState = CallState.CONNECTING;
			updateCallActivity();

			isRecording = false;
			cleanupStreamsForReconnect();

			long delay = reconnectAttempts * 2000L;
			executorService.execute(() -> {
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}

				if (callState != CallState.CONNECTING) return;
				if (voiceCallKey == null) {
					isReconnecting = false;
					return;
				}

				try {
					boolean alice = !isIncoming;

					if (lastRemoteOnion != null) {
						torConnection = connectionManager.connectToRemote(
								callId, lastRemoteOnion, reconnectKey, alice);
					} else if (onionAddress != null) {
						VoiceCallConnectionHandler handler = conn -> {
							torConnection = conn;
							reconnectAttempts = 0;
							isReconnecting = false;
							callState = CallState.CONNECTED;
							updateCallActivity();
							deriveAudioEncryptionKeys();
							startAudioStreaming();
						};
						connectionManager.createIncomingEndpoint(
								callId, reconnectKey, alice, handler);
						isReconnecting = false;
						return;
					} else {
						throw new IOException("No reconnection target");
					}

					if (torConnection != null) {
						reconnectAttempts = 0;
						isReconnecting = false;
						callState = CallState.CONNECTED;
						updateCallActivity();
						deriveAudioEncryptionKeys();
						startAudioStreaming();
					} else {
						isReconnecting = false;
						handleConnectionError();
					}
				} catch (Exception e) {
					isReconnecting = false;
					handleConnectionError();
				}
			});
		} else {
			callState = CallState.DISCONNECTED;
			updateCallActivity();
			mainHandler.postDelayed(() -> {
				if (callState == CallState.DISCONNECTED) {
					endCall();
				}
			}, 2000);
		}
	}

	private static void writeToJitterBuffer(byte[] jitterBuffer, byte[] data,
			int writePos, int bufferSize) {
		int toWrite = Math.min(data.length, bufferSize);
		for (int i = 0; i < toWrite; i++) {
			jitterBuffer[(writePos + i) % bufferSize] = data[i];
		}
	}

	private void cleanupStreamsForReconnect() {
		synchronized (streamLock) {
			if (torConnection != null) {
				try {
					torConnection.getReader().dispose(false, true);
					torConnection.getWriter().dispose(false);
				} catch (Exception e) {
				}
				torConnection = null;
			}
			if (audioSocket != null) {
				try {
					audioSocket.close();
				} catch (Exception e) {
				}
				audioSocket = null;
			}
		}
	}

	
	private void zeroizeKeyMaterial() {
		if (voiceCallKey != null) {
			Arrays.fill(voiceCallKey.getBytes(), (byte) 0);
			voiceCallKey = null;
		}
		if (audioKeys != null) {
			Arrays.fill(audioKeys.txKey.getBytes(), (byte) 0);
			Arrays.fill(audioKeys.rxKey.getBytes(), (byte) 0);
			audioKeys = null;
		}
		if (localEphemeralSecret != null) {
			Arrays.fill(localEphemeralSecret, (byte) 0);
			localEphemeralSecret = null;
		}
		if (remoteEphemeralSecret != null) {
			Arrays.fill(remoteEphemeralSecret, (byte) 0);
			remoteEphemeralSecret = null;
		}
		synchronized (jbLock) {
			Arrays.fill(sharedJitterBuffer, (byte) 0);
			jbWritePos = 0;
			jbReadPos = 0;
			jbBufferedBytes = 0;
		}
		contactName = null;
	}

	private void stopAudioStreaming() {
		audioManager.setMode(AudioManager.MODE_NORMAL);
		if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
			proximityWakeLock.release();
		}

		releaseAudioProcessing();

		synchronized (streamLock) {
			isRecording = false;

			if (audioRecord != null) {
				try {
					if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
						audioRecord.stop();
					}
					audioRecord.release();
				} catch (IllegalStateException e) {
				} catch (Exception e) {
				} finally {
					audioRecord = null;
				}
			}

			if (audioTrack != null) {
				try {
					if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
						audioTrack.stop();
					}
					audioTrack.release();
				} catch (IllegalStateException e) {
				} catch (Exception e) {
				} finally {
					audioTrack = null;
				}
			}

			if (opusEncoder != null) {
				try {
					opusEncoder.release();
				} catch (Exception e) {
				} finally {
					opusEncoder = null;
				}
			}

			if (opusDecoder != null) {
				try {
					opusDecoder.release();
				} catch (Exception e) {
				} finally {
					opusDecoder = null;
				}
			}


			if (audioSocket != null) {
				try {
					audioSocket.close();
				} catch (IOException e) {
				} catch (Exception e) {
				} finally {
					audioSocket = null;
				}
			}

			if (torConnection != null) {
				try {
					torConnection.getReader().dispose(false, true);
					torConnection.getWriter().dispose(false);
				} catch (IOException e) {
				} catch (Exception e) {
				} finally {
					torConnection = null;
				}
			}

			if (serverSocket != null && !serverSocket.isClosed()) {
				try {
					serverSocket.close();
				} catch (IOException e) {
				} catch (Exception e) {
				} finally {
					serverSocket = null;
				}
			}
		}
	}

	private void sendCallOffer() throws DbException {
		String encodedKey = voiceCallCrypto.encodeVoiceCallKey(voiceCallKey);
		String payload = encodedKey;
		if (localEphemeralSecret != null) {
			payload = encodedKey + "|" + bytesToHex(localEphemeralSecret);
		}
		sendVoiceSignal(VoiceSignalType.CALL_OFFER, payload);
	}

	private void sendCallAnswer() throws DbException {
		String payload = onionAddress + ":" + onionPort;
		if (localEphemeralSecret != null) {
			payload = payload + "|" + bytesToHex(localEphemeralSecret);
		}
		sendVoiceSignal(VoiceSignalType.CALL_ANSWER, payload);
	}

	private void sendCallReject() throws DbException {
		sendVoiceSignal(VoiceSignalType.CALL_REJECT, null);
	}

	private void sendCallEnd() throws DbException {
		Long durationMs = null;
		if (callStartTime > 0) {
			durationMs = System.currentTimeMillis() - callStartTime;
		}
		sendVoiceSignalWithDuration(VoiceSignalType.CALL_END, null, durationMs);
	}
	private void sendVoiceSignal(VoiceSignalType signalType, String payload) {
		sendVoiceSignalWithDuration(signalType, payload, null);
	}

	private void sendVoiceSignalWithDuration(VoiceSignalType signalType,
			String payload, Long durationMs) {
		dbExecutor.execute(() -> {
			try {
				int retries = 10;
				while (conversationGroup == null && retries > 0) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						handleSignalingFailure("Interrupted while waiting for conversation group");
						return;
					}
					retries--;
				}

				if (conversationGroup == null) {
					handleSignalingFailure("Failed to load conversation group");
					return;
				}

				GroupId groupId = conversationGroup.getId();
				long timestamp = System.currentTimeMillis();

				org.briarproject.briar.api.messaging.VoiceSignal signal;
				switch (signalType) {
					case CALL_OFFER:
						signal = voiceSignalFactory.createCallOffer(
								groupId, timestamp, callId, payload);
						break;
					case CALL_ANSWER:
						signal = voiceSignalFactory.createCallAnswer(
								groupId, timestamp, callId, payload);
						break;
					case CALL_REJECT:
						signal = voiceSignalFactory.createCallReject(
								groupId, timestamp, callId);
						break;
					case CALL_END:
						signal = voiceSignalFactory.createCallEnd(
								groupId, timestamp, callId, durationMs);
						break;
					case ICE_CANDIDATE:
						signal = voiceSignalFactory.createIceCandidate(
								groupId, timestamp, callId, payload);
						break;
					case CALL_BUSY:
						signal = voiceSignalFactory.createCallBusy(
								groupId, timestamp, callId);
						break;
					default:
						handleSignalingFailure("Unknown signal type: " + signalType);
						return;
				}

				messagingManager.addLocalVoiceSignal(signal);
			} catch (Exception e) {
				handleSignalingFailure("Failed to send signaling: " + e.getMessage());
			}
		});
	}

	private void handleSignalingFailure(String reason) {
		mainHandler.post(() -> {
			if (callState == CallState.CONNECTING || callState == CallState.RINGING) {
				callState = CallState.FAILED;
				updateCallActivity();
				if (callActivity != null) {
					callActivity.onCallFailed("Failed to establish call: " + reason);
				}
			}
		});
	}

	public void handleIncomingSignaling(String message) {
		VoiceCallSignal signal;
		if (voiceCallKey != null) {
			signal = VoiceCallSignal.fromWireFormat(message,
					voiceCallCrypto.encodeVoiceCallKey(voiceCallKey)
							.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} else {
			signal = VoiceCallSignal.fromWireFormat(message);
		}
		if (signal == null) {
			return;
		}
		if (callId == null) {
			return;
		}

		if (!callId.equals(signal.getCallId())) {
			return;
		}

		switch (signal.getType()) {
			case CALL_ANSWER:
				String remoteOnion = signal.getOnionAddress();
				Integer remotePort = signal.getOnionPort();
				if (remoteOnion != null && remotePort != null) {
					String ephHex = signal.getEphemeralSecret();
					if (ephHex != null) {
						remoteEphemeralSecret = hexToBytes(ephHex);
					}
					callState = CallState.CONNECTING;
					updateCallActivity();
					connectToRemoteOnion(remoteOnion, remotePort);
				}
				break;

			case CALL_REJECT:
				callState = CallState.DISCONNECTED;
				updateCallActivity();
				stopSelf();
				break;

			case CALL_END:
				endCall();
				break;

			default:
				break;
		}
	}

	private void updateCallActivity() {
		mainHandler.post(() -> {
			if (callActivity != null) {
				switch (callState) {
					case CONNECTED:
						callActivity.onCallConnected();
						break;
					case DISCONNECTED:
						callActivity.onCallDisconnected();
						break;
					case FAILED:
						callActivity.onCallFailed("Connection failed");
						break;
				}
			}
		});
	}

	public void setCallActivity(VoiceCallActivity activity) {
		this.callActivity = activity;
	}

	public void clearCallActivity() {
		this.callActivity = null;
	}

	public CallState getCallState() {
		return callState;
	}

	public void setMuted(boolean muted) {
		this.isMuted = muted;
	}

	@SuppressWarnings("deprecation")
	public void setSpeakerphoneOn(boolean speakerOn) {
		this.isSpeakerOn = speakerOn;
		if (audioManager == null) return;
		if (android.os.Build.VERSION.SDK_INT >= 31) {
			if (speakerOn) {
				for (android.media.AudioDeviceInfo device :
						audioManager.getAvailableCommunicationDevices()) {
					if (device.getType() ==
							android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
						audioManager.setCommunicationDevice(device);
						break;
					}
				}
			} else {
				audioManager.clearCommunicationDevice();
			}
		} else {
			audioManager.setSpeakerphoneOn(speakerOn);
		}
	}

	public boolean isSpeakerphoneOn() {
		return isSpeakerOn;
	}

	public NetworkMetrics getNetworkMetrics() {
		return networkMetrics;
	}

	private void launchCallActivity() {
		Intent intent = new Intent(this, VoiceCallActivity.class);
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contactId.getInt());
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_NAME, contactName);
		intent.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, isIncoming);
		intent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(intent);
	}

	private Notification createNotification() {
		Intent intent = new Intent(this, VoiceCallActivity.class);
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contactId.getInt());
		intent.putExtra(VoiceCallActivity.EXTRA_CONTACT_NAME, contactName);
		intent.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, isIncoming);
		intent.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
				intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		String title = isIncoming && callState == CallState.RINGING ?
				"Incoming call" : "Ongoing call";
		String text = "Secure voice call";

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(title)
				.setContentText(text)
				.setSmallIcon(R.drawable.ic_phone_white)
				.setPriority(NotificationCompat.PRIORITY_MAX)
				.setCategory(NotificationCompat.CATEGORY_CALL)
				.setOngoing(true)
				.setAutoCancel(false)
				.setContentIntent(pendingIntent);
		if (isIncoming && callState == CallState.RINGING) {
			builder.setFullScreenIntent(pendingIntent, true);
			Intent acceptIntent = new Intent(this, VoiceCallService.class);
			acceptIntent.setAction("ACTION_ACCEPT_CALL");
			PendingIntent acceptPendingIntent = PendingIntent.getService(this, 1,
					acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			builder.addAction(R.drawable.ic_phone_white, "Accept", acceptPendingIntent);
			Intent declineIntent = new Intent(this, VoiceCallService.class);
			declineIntent.setAction("ACTION_DECLINE_CALL");
			PendingIntent declinePendingIntent = PendingIntent.getService(this, 2,
					declineIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			builder.addAction(R.drawable.ic_close, "Decline", declinePendingIntent);
			builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
		}

		return builder.build();
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(
					CHANNEL_ID,
					"Voice Calls",
					NotificationManager.IMPORTANCE_HIGH);
			channel.setDescription("Notifications for incoming and ongoing voice calls");
			channel.enableLights(true);
			channel.enableVibration(true);
			channel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
			channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

			NotificationManager manager = getSystemService(NotificationManager.class);
			if (manager != null) {
				manager.createNotificationChannel(channel);
			}
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		isShuttingDown = true;
		cancelCallSetupTimeout();

		if (eventBus != null) {
			eventBus.removeListener(this);
		}

		if (mainHandler != null) {
			mainHandler.removeCallbacksAndMessages(null);
		}

		stopAudioStreaming();
		if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
			proximityWakeLock.release();
		}
		zeroizeKeyMaterial();

		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
				executorService.shutdownNow();
				if (!executorService.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
				}
			}
		} catch (InterruptedException e) {
			executorService.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}


	@Override
	public void eventOccurred(Event e) {
		if (e instanceof VoiceSignalReceivedEvent) {
			VoiceSignalReceivedEvent event = (VoiceSignalReceivedEvent) e;

			if (contactId != null && event.getContactId().equals(contactId)) {
				VoiceSignalHeader header = event.getSignalHeader();
				mainHandler.post(() -> handleIncomingVoiceSignal(header));
			}
		}
		else if (e instanceof PrivateMessageReceivedEvent) {
			PrivateMessageReceivedEvent event = (PrivateMessageReceivedEvent) e;

			if (contactId != null && event.getContactId().equals(contactId)) {
				dbExecutor.execute(() -> {
					try {
						MessageId messageId = event.getMessageHeader().getId();
						String text = messagingManager.getMessageText(messageId);

						if (text != null && (text.startsWith("VOICE_CALL:") ||
								VoiceCallSignal.isSignal(text))) {
							new Handler(Looper.getMainLooper()).post(() ->
								handleIncomingSignaling(text)
							);
						}
					} catch (DbException ex) {
					}
				});
			}
		}
	}

	private void handleIncomingVoiceSignal(VoiceSignalHeader header) {
		String signalCallId = header.getCallId();

		switch (header.getSignalType()) {
			case CALL_OFFER:
				break;

			case CALL_ANSWER:
				if (callId == null || !callId.equals(signalCallId)) {
					return;
				}
				String payload = header.getPayload();
				if (payload != null) {
					String ephemeralPart = null;
					String connectionPart = payload;
					int pipeIdx = payload.indexOf('|');
					if (pipeIdx > 0) {
						connectionPart = payload.substring(0, pipeIdx);
						ephemeralPart = payload.substring(pipeIdx + 1);
					}
					String[] parts = connectionPart.split(":");
					if (parts.length >= 2) {
						String remoteOnion = parts[0];
						try {
							int remotePort = Integer.parseInt(parts[1]);
							if (remotePort < 1 || remotePort > 65535) {
								break;
							}
							if (ephemeralPart != null) {
								remoteEphemeralSecret = hexToBytes(ephemeralPart);
							}
							callState = CallState.CONNECTING;
							updateCallActivity();
							connectToRemoteOnion(remoteOnion, remotePort);
						} catch (NumberFormatException e) {
							break;
						}
					}
				}
				break;

			case CALL_REJECT:
				if (callId == null || !callId.equals(signalCallId)) {
					return;
				}
				callState = CallState.DISCONNECTED;
				updateCallActivity();
				stopSelf();
				break;

			case CALL_END:
				if (callId == null || !callId.equals(signalCallId)) {
					return;
				}
				endCall();
				break;

			case CALL_BUSY:
				if (callId == null || !callId.equals(signalCallId)) {
					return;
				}
				callState = CallState.DISCONNECTED;
				updateCallActivity();
				if (callActivity != null) {
					callActivity.onCallFailed("Contact is busy");
				}
				stopSelf();
				break;

			default:
				break;
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder hex = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private static byte[] hexToBytes(String hex) {
		int len = hex.length();
		if (len % 2 != 0) {
			throw new IllegalArgumentException("Invalid hex string length");
		}
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
					+ Character.digit(hex.charAt(i + 1), 16));
		}
		return data;
	}
}