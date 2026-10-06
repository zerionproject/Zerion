package com.professor.zerion.android.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import com.professor.zerion.android.conversation.voice.VoiceCallActivity;
import com.professor.zerion.android.logout.ExitActivity;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.splash.SplashScreenActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AppTasksTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeScreen extends BaseActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
		}

		@Override
		public void onCreate(@Nullable Bundle state) {
			super.onCreate(state);
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	private static ActivityManager.AppTask task(int id, Class<?> base) {
		ActivityManager.AppTask t = ShadowAppTask.newInstance();
		ActivityManager.RecentTaskInfo info =
				new ActivityManager.RecentTaskInfo();
		info.taskId = id;
		info.baseActivity = new ComponentName("com.professor.zerion",
				base.getName());
		shadowOf(t).setTaskInfo(info);
		return t;
	}

	private void setTasks(ActivityManager.AppTask... tasks) {
		ActivityManager am = (ActivityManager)
				ctx.getSystemService(Context.ACTIVITY_SERVICE);
		shadowOf(am).setAppTasks(new ArrayList<>(Arrays.asList(tasks)));
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void theMainTaskIsBroughtBackRatherThanReplaced() {
		Activity from = Robolectric.buildActivity(Activity.class).create()
				.get();
		ActivityManager.AppTask main = task(from.getTaskId() + 1,
				NavDrawerActivity.class);
		ActivityManager.AppTask call = task(from.getTaskId() + 2,
				VoiceCallActivity.class);
		setTasks(call, main);
		assertTrue(AppTasks.moveMainTaskToFront(from));
		assertTrue(shadowOf(main).hasMovedToFront());
		assertFalse(shadowOf(call).hasMovedToFront());
	}

	@Test
	public void withoutAMainTaskNothingIsMoved() {
		Activity from = Robolectric.buildActivity(Activity.class).create()
				.get();
		ActivityManager.AppTask call = task(from.getTaskId() + 2,
				VoiceCallActivity.class);
		setTasks(call);
		assertFalse(AppTasks.moveMainTaskToFront(from));
		assertFalse(shadowOf(call).hasMovedToFront());
	}

	@Test
	public void aFreshStartClosesOtherTasksButNotACall() {
		Intent fresh = new Intent(ctx, ProbeScreen.class);
		fresh.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_CLEAR_TASK);
		ActivityController<ProbeScreen> c =
				Robolectric.buildActivity(ProbeScreen.class, fresh);
		int own = c.get().getTaskId();
		ActivityManager.AppTask self = task(own, ProbeScreen.class);
		ActivityManager.AppTask main = task(own + 1, NavDrawerActivity.class);
		ActivityManager.AppTask call = task(own + 2, VoiceCallActivity.class);
		setTasks(self, main, call);
		c.setup();
		idle();
		assertTrue("the old main task is closed",
				shadowOf(main).isFinishedAndRemoved());
		assertFalse("a running call is left alone",
				shadowOf(call).isFinishedAndRemoved());
		assertFalse(shadowOf(self).isFinishedAndRemoved());
	}

	@Test
	public void anOrdinaryStartClosesNothing() {
		ActivityController<ProbeScreen> c =
				Robolectric.buildActivity(ProbeScreen.class);
		int own = c.get().getTaskId();
		ActivityManager.AppTask main = task(own + 1, NavDrawerActivity.class);
		setTasks(task(own, ProbeScreen.class), main);
		c.setup();
		idle();
		assertFalse(shadowOf(main).isFinishedAndRemoved());
	}

	@Test
	public void exitClosesEveryTaskIncludingACall() {
		ActivityController<ExitActivity> c =
				Robolectric.buildActivity(ExitActivity.class);
		int own = c.get().getTaskId();
		ActivityManager.AppTask main = task(own + 1, NavDrawerActivity.class);
		ActivityManager.AppTask call = task(own + 2, VoiceCallActivity.class);
		setTasks(task(own, ExitActivity.class), main, call);
		AppTasks.finishOtherTasks(c.get(), true);
		assertTrue(shadowOf(main).isFinishedAndRemoved());
		assertTrue(shadowOf(call).isFinishedAndRemoved());
	}

	@Test
	public void theLauncherReturnsToTheOpenMainTask() {
		ActivityController<SplashScreenActivity> c =
				Robolectric.buildActivity(SplashScreenActivity.class);
		int own = c.get().getTaskId();
		ActivityManager.AppTask main = task(own + 1, NavDrawerActivity.class);
		setTasks(task(own, SplashScreenActivity.class), main);
		c.setup();
		shadowOf(Looper.getMainLooper()).idleFor(
				java.time.Duration.ofSeconds(10));
		assertTrue("the open main task is brought back",
				shadowOf(main).hasMovedToFront());
		Intent next = shadowOf(c.get()).getNextStartedActivity();
		List<String> started = new ArrayList<>();
		while (next != null) {
			started.add(next.getComponent() == null ? "?"
					: next.getComponent().getClassName());
			next = shadowOf(c.get()).getNextStartedActivity();
		}
		assertFalse("a second main screen was opened: " + started,
				started.contains(NavDrawerActivity.class.getName()));
	}
}
