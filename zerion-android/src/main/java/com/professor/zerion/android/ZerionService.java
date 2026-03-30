package com.professor.zerion.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.view.WindowManager;

import com.bumptech.glide.Glide;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.AndroidUtils;
import com.professor.zerion.R;
import com.professor.zerion.android.logout.HideUiActivity;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.content.Intent.ACTION_SHUTDOWN;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Process.myPid;
import static androidx.core.app.NotificationCompat.VISIBILITY_SECRET;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.ALREADY_RUNNING;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult.SUCCESS;
import static org.briarproject.bramble.util.AndroidUtils.isUiThread;
import static com.professor.zerion.android.ZerionApplication.ENTRY_ACTIVITY;
import static com.professor.zerion.android.api.AndroidNotificationManager.ONGOING_CHANNEL_ID;
import static com.professor.zerion.android.api.AndroidNotificationManager.ONGOING_CHANNEL_OLD_ID;
import static com.professor.zerion.android.api.AndroidNotificationManager.ONGOING_NOTIFICATION_ID;
import static com.professor.zerion.android.api.LockManager.ACTION_LOCK;
import static com.professor.zerion.android.api.LockManager.EXTRA_PID;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

public class ZerionService extends Service {

	public static String EXTRA_START_RESULT =
			"org.briarproject.briar.START_RESULT";
	public static String EXTRA_STARTUP_FAILED =
			"org.briarproject.briar.STARTUP_FAILED";

	private static final long MIN_GLIDE_CACHE_CLEAR_INTERVAL_MS = 5000;

	private final AtomicBoolean created = new AtomicBoolean(false);
	private final Binder binder = new ZerionBinder();

	@Nullable
	private BroadcastReceiver receiver = null;
	private ZerionApplication app;

	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	AccountManager accountManager;
	@Inject
	LockManager lockManager;
	@Inject
	AndroidWakeLockManager wakeLockManager;

	@Inject
	volatile LifecycleManager lifecycleManager;
	@Inject
	volatile AndroidExecutor androidExecutor;
	@Inject
	volatile Clock clock;

	private volatile boolean started = false;
	private volatile long glideCacheCleared = 0;

	@Override
	public void onCreate() {
		super.onCreate();

		app = (ZerionApplication) getApplication();
		app.getApplicationComponent().inject(this);

		if (created.getAndSet(true)) {
			stopSelf();
			return;
		}
		final SecretKey dbKey = accountManager.getDatabaseKey();
		if (dbKey == null) {
			stopSelf();
			return;
		}

		wakeLockManager.runWakefully(() -> {
				if (SDK_INT >= 26) {
					NotificationManager nm = (NotificationManager)
							requireNonNull(getSystemService(NOTIFICATION_SERVICE));
					nm.deleteNotificationChannel(ONGOING_CHANNEL_OLD_ID);

					NotificationChannel ongoingChannel = new NotificationChannel(
							ONGOING_CHANNEL_ID,
							getString(R.string.ongoing_notification_title),
							IMPORTANCE_LOW);
					ongoingChannel.setLockscreenVisibility(VISIBILITY_SECRET);
					ongoingChannel.setShowBadge(false);
					ongoingChannel.enableVibration(false);
					ongoingChannel.setSound(null, null);
					ongoingChannel.enableLights(false);
					nm.createNotificationChannel(ongoingChannel);
				}
				Notification foregroundNotification =
						notificationManager.getForegroundNotification();
				startForeground(ONGOING_NOTIFICATION_ID, foregroundNotification);

				wakeLockManager.executeWakefully(() -> {
					StartResult result = lifecycleManager.startServices(dbKey);
					if (result == SUCCESS) {
						started = true;
					} else if (result == ALREADY_RUNNING) {
						shutdownFromBackground();
					} else {
						showStartupFailure(result);
						stopSelf();
					}
				}, "LifecycleStartup");

				receiver = new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						shutdownFromBackground();
					}
				};
				IntentFilter filter = new IntentFilter();
				filter.addAction(ACTION_SHUTDOWN);
				filter.addAction("android.intent.action.QUICKBOOT_POWEROFF");
				filter.addAction("com.htc.intent.action.QUICKBOOT_POWEROFF");

				AndroidUtils.registerReceiver(getApplicationContext(), receiver, filter);
		}, "LifecycleStartup");
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(Localizer.getInstance().applyLocaleToContext(base));
	}

	private void showStartupFailure(StartResult result) {
		androidExecutor.runOnUiThread(() -> {
			Intent i = new Intent(ZerionService.this, ENTRY_ACTIVITY);
			i.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);
			i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
			i.putExtra(EXTRA_STARTUP_FAILED, true);
			i.putExtra(EXTRA_START_RESULT, result.name());
			startActivity(i);
		});
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_LOCK.equals(intent.getAction())) {
			int pid = intent.getIntExtra(EXTRA_PID, -1);
			if (pid == myPid()) {
				lockManager.setLocked(true);
			}
		}
		return START_NOT_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		shutdown(false);
		stopForeground(true);
		if (receiver != null) {
			try {
				getApplicationContext().unregisterReceiver(receiver);
			} catch (IllegalArgumentException e) {
			}
		}
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		maybeClearGlideCache();
		if (app.isRunningInBackground()) hideUi();
	}

	@Override
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);

		if (level == TRIM_MEMORY_UI_HIDDEN) {
		} else if (level == TRIM_MEMORY_BACKGROUND) {
		} else if (level == TRIM_MEMORY_MODERATE) {
		} else if (level == TRIM_MEMORY_COMPLETE) {
		} else if (level == TRIM_MEMORY_RUNNING_MODERATE) {
		} else if (level == TRIM_MEMORY_RUNNING_LOW) {
			maybeClearGlideCache();
		} else if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
			maybeClearGlideCache();
			if (app.isRunningInBackground()) hideUi();
		}
	}

	private void maybeClearGlideCache() {
		if (isUiThread()) {
			maybeClearGlideCacheUiThread();
		} else {
			androidExecutor.runOnUiThread(this::maybeClearGlideCacheUiThread);
		}
	}

	@UiThread
	private void maybeClearGlideCacheUiThread() {
		long now = clock.currentTimeMillis();

		if (now - glideCacheCleared >= MIN_GLIDE_CACHE_CLEAR_INTERVAL_MS) {
			Glide.get(getApplicationContext()).clearMemory();
			glideCacheCleared = now;
		}
	}

	private void hideUi() {
		Intent i = new Intent(this, HideUiActivity.class);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK
				| FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
				| FLAG_ACTIVITY_NO_ANIMATION
				| FLAG_ACTIVITY_CLEAR_TASK);

		startActivity(i);
	}

	private void shutdownFromBackground() {
		wakeLockManager.runWakefully(() -> {
				shutdown(true);
				hideUi();
				wakeLockManager.executeWakefully(() -> {
					try {
						if (started) lifecycleManager.waitForShutdown();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}

					if (!app.isInstrumentationTest()) {
						androidExecutor.runOnUiThread(() -> {
							stopSelf();
						});
					}

					Thread killWatchdog = new Thread(() -> {
						try {
							Thread.sleep(3000);
						} catch (InterruptedException e) {
							return;
						}
						android.os.Process.killProcess(android.os.Process.myPid());
					});
					killWatchdog.setDaemon(true);
					killWatchdog.setName("ShutdownWatchdog");
					killWatchdog.start();
				}, "BackgroundShutdown");
		}, "BackgroundShutdown");
	}

	public void waitForStartup() throws InterruptedException {
		lifecycleManager.waitForStartup();
	}

	public void waitForShutdown() throws InterruptedException {
		lifecycleManager.waitForShutdown();
	}

	public void shutdown(boolean stopAndroidService) {
		wakeLockManager.runWakefully(() -> {
				wakeLockManager.executeWakefully(() -> {
					if (started) lifecycleManager.stopServices();
					if (stopAndroidService) {
						androidExecutor.runOnUiThread(() -> stopSelf());
					}
				}, "LifecycleShutdown");
		}, "LifecycleShutdown");
	}

	public class ZerionBinder extends Binder {
		public ZerionService getService() {
			return ZerionService.this;
		}
	}

	public static class ZerionServiceConnection implements ServiceConnection {
		private final CountDownLatch binderLatch = new CountDownLatch(1);
		private volatile IBinder binder = null;

		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			this.binder = binder;
			binderLatch.countDown();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
		}

		public IBinder waitForBinder() throws InterruptedException {
			binderLatch.await();
			return binder;
		}
	}
}
