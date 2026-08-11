package org.zerionproject.core.system;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.system.AlarmListener;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.system.Wakeful;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.INTERVAL_FIFTEEN_MINUTES;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.content.Context.ALARM_SERVICE;
import static android.os.Build.VERSION.SDK_INT;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.system.AlarmConstants.EXTRA_PID;
import static org.zerionproject.core.system.AlarmConstants.REQUEST_ALARM;
import static org.zerionproject.core.util.AndroidUtils.getImmutableFlags;

@ThreadSafe
@NotNullByDefault
class AndroidTaskScheduler implements TaskScheduler, Service, AlarmListener {

	private static final long ALARM_MS = INTERVAL_FIFTEEN_MINUTES;

	private final Application app;
	private final AndroidWakeLockManager wakeLockManager;
	private final ScheduledExecutorService scheduledExecutorService;
	private final AlarmManager alarmManager;

	private final Object lock = new Object();
	@GuardedBy("lock")
	private final Queue<ScheduledTask> tasks = new PriorityQueue<>();

	AndroidTaskScheduler(Application app,
			AndroidWakeLockManager wakeLockManager,
			ScheduledExecutorService scheduledExecutorService) {
		this.app = app;
		this.wakeLockManager = wakeLockManager;
		this.scheduledExecutorService = scheduledExecutorService;
		alarmManager = (AlarmManager)
				requireNonNull(app.getSystemService(ALARM_SERVICE));
	}

	@Override
	public void startService() {
		scheduleAlarm();
	}

	@Override
	public void stopService() {
		cancelAlarm();
	}

	@Override
	public Cancellable schedule(Runnable task, Executor executor, long delay,
			TimeUnit unit) {
		AtomicBoolean cancelled = new AtomicBoolean(false);
		return schedule(task, executor, delay, unit, cancelled);
	}

	@Override
	public Cancellable scheduleWithFixedDelay(Runnable task, Executor executor,
			long delay, long interval, TimeUnit unit) {
		AtomicBoolean cancelled = new AtomicBoolean(false);
		return scheduleWithFixedDelay(task, executor, delay, interval, unit,
				cancelled);
	}

	@Override
	public void onAlarm(Intent intent) {
		wakeLockManager.runWakefully(() -> {
			int extraPid = intent.getIntExtra(EXTRA_PID, -1);
			int currentPid = Process.myPid();
			if (extraPid == currentPid) {
				rescheduleAlarm();
				runDueTasks();
			}
		}, "TaskAlarm");
	}

	private Cancellable schedule(Runnable task, Executor executor, long delay,
			TimeUnit unit, AtomicBoolean cancelled) {
		long now = SystemClock.elapsedRealtime();
		long dueMillis = now + MILLISECONDS.convert(delay, unit);
		Runnable wakeful = () ->
				wakeLockManager.executeWakefully(task, executor, "TaskHandoff");
		ScheduledTask s;
		synchronized (lock) {
			Future<?> check = scheduleCheckForDueTasks(delay, unit);
			s = new ScheduledTask(wakeful, dueMillis, check, cancelled);
			tasks.add(s);
		}
		return s;
	}

	private Cancellable scheduleWithFixedDelay(Runnable task, Executor executor,
			long delay, long interval, TimeUnit unit, AtomicBoolean cancelled) {
		Runnable wrapped = () -> {
			task.run();
			scheduleWithFixedDelay(task, executor, interval, interval, unit,
					cancelled);
		};
		return schedule(wrapped, executor, delay, unit, cancelled);
	}

	@GuardedBy("lock")
	private Future<?> scheduleCheckForDueTasks(long delay, TimeUnit unit) {
		Runnable wakeful = () -> wakeLockManager.runWakefully(
				this::runDueTasks, "TaskScheduler");
		return scheduledExecutorService.schedule(wakeful, delay, unit);
	}

	@Wakeful
	private void runDueTasks() {
		long now = SystemClock.elapsedRealtime();
		List<ScheduledTask> due = new ArrayList<>();
		synchronized (lock) {
			while (true) {
				ScheduledTask s = tasks.peek();
				if (s == null || s.dueMillis > now) break;
				due.add(tasks.remove());
			}
		}
		for (ScheduledTask s : due) {
			s.run();
		}
	}

	private void scheduleAlarm() {
		if (SDK_INT >= 23) scheduleIdleAlarm();
		else scheduleInexactRepeatingAlarm();
	}

	private void rescheduleAlarm() {
		if (SDK_INT >= 23) scheduleIdleAlarm();
	}

	private void cancelAlarm() {
		alarmManager.cancel(getAlarmPendingIntent());
	}

	private void scheduleInexactRepeatingAlarm() {
		alarmManager.setInexactRepeating(ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + ALARM_MS, ALARM_MS,
				getAlarmPendingIntent());
	}

	@TargetApi(23)
	private void scheduleIdleAlarm() {
		alarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + ALARM_MS,
				getAlarmPendingIntent());
	}

	private PendingIntent getAlarmPendingIntent() {
		Intent i = new Intent(app, AlarmReceiver.class);
		i.putExtra(EXTRA_PID, android.os.Process.myPid());
		return PendingIntent.getBroadcast(app, REQUEST_ALARM, i,
				getImmutableFlags(FLAG_CANCEL_CURRENT));
	}

	private class ScheduledTask
			implements Runnable, Cancellable, Comparable<ScheduledTask> {

		private final Runnable task;
		private final long dueMillis;
		private final Future<?> check;
		private final AtomicBoolean cancelled;

		private ScheduledTask(Runnable task, long dueMillis,
				Future<?> check, AtomicBoolean cancelled) {
			this.task = task;
			this.dueMillis = dueMillis;
			this.check = check;
			this.cancelled = cancelled;
		}

		@Override
		public void run() {
			if (!cancelled.get()) task.run();
		}

		@Override
		public void cancel() {
			cancelled.set(true);
			check.cancel(false);
			synchronized (lock) {
				tasks.remove(this);
			}
		}

		@Override
		public int compareTo(ScheduledTask s) {
			if (dueMillis < s.dueMillis) return -1;
			if (dueMillis > s.dueMillis) return 1;
			return 0;
		}
	}
}
