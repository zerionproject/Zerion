package com.professor.zerion.android.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.conversation.voice.VoiceCallActivity;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public final class AppTasks {

	private AppTasks() {
	}

	private static List<ActivityManager.AppTask> tasks(Context ctx) {
		ActivityManager am = (ActivityManager)
				ctx.getSystemService(Context.ACTIVITY_SERVICE);
		if (am == null) return Collections.emptyList();
		try {
			return am.getAppTasks();
		} catch (RuntimeException e) {
			return Collections.emptyList();
		}
	}

	@Nullable
	private static ComponentName base(ActivityManager.AppTask task) {
		try {
			ActivityManager.RecentTaskInfo info = task.getTaskInfo();
			if (info.baseActivity != null) return info.baseActivity;
			Intent base = info.baseIntent;
			return base == null ? null : base.getComponent();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static int id(ActivityManager.AppTask task) {
		try {
			return task.getTaskInfo().taskId;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static boolean is(@Nullable ComponentName c, Class<?> cls) {
		return c != null && cls.getName().equals(c.getClassName());
	}

	public static boolean moveMainTaskToFront(Activity from) {
		for (ActivityManager.AppTask task : tasks(from)) {
			if (id(task) == from.getTaskId()) continue;
			if (!is(base(task), ZerionApplication.ENTRY_ACTIVITY)) continue;
			try {
				task.moveToFront();
				return true;
			} catch (RuntimeException e) {
				return false;
			}
		}
		return false;
	}

	public static void finishOtherTasks(Activity keep, boolean closeCalls) {
		for (ActivityManager.AppTask task : tasks(keep)) {
			if (id(task) == keep.getTaskId()) continue;
			if (!closeCalls && is(base(task), VoiceCallActivity.class)) {
				continue;
			}
			try {
				task.finishAndRemoveTask();
			} catch (RuntimeException ignored) {
			}
		}
	}

	public static boolean startsAfresh(Activity a, boolean restored) {
		Intent i = a.getIntent();
		if (restored || i == null) return false;
		int flags = i.getFlags();
		return (flags & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0
				&& (flags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
	}
}
