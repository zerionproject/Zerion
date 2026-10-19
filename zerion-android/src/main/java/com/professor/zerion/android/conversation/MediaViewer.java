package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.util.ExternalViewerTypes;
import com.professor.zerion.android.util.SafeImageDecoder;

import org.briarproject.nullsafety.NotNullByDefault;

import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import androidx.annotation.VisibleForTesting;

import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
public final class MediaViewer {

	static final String EXTRA_TOKEN = "zerion.MEDIA";

	static final int MAX_SHOWN = 4;

	static final long START_GRACE_MS = 2000L;

	static final long REMOVED_FOR_MS = 10L * 60L * 1000L;

	static final int MAX_REMOVED = 512;

	private static final String[] PLAYABLE_VIDEO_TYPES = {
			"video/mp4", "video/webm", "video/3gpp", "video/quicktime",
			"video/x-matroska", "video/mpeg", "video/avi"
	};

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Object LOCK = new Object();
	private static final Map<String, Shown> SHOWN = new LinkedHashMap<>();
	private static final Map<String, Runnable> WATCHERS = new HashMap<>();
	private static final Set<String> OPENING = new HashSet<>();
	private static final Map<String, Runnable> TIMERS = new HashMap<>();
	private static final LinkedHashMap<String, Long> REMOVED =
			new LinkedHashMap<>();
	private static long generation = 0L;
	@Nullable
	private static WeakReference<Application> watched;
	private static final Handler MAIN = new Handler(Looper.getMainLooper());

	@VisibleForTesting
	static LongSupplier clock = System::currentTimeMillis;

	@VisibleForTesting
	static UnaryOperator<byte[]> copier = byte[]::clone;

	private static final Executor WIPE_THREAD =
			Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "viewer-wipe");
				t.setDaemon(true);
				return t;
			});

	static final Executor DEFAULT_WIPER = task -> {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			WIPE_THREAD.execute(task);
		} else {
			task.run();
		}
	};

	@VisibleForTesting
	static Executor wiper = DEFAULT_WIPER;

	public static final class Item {

		final byte[] content;
		@Nullable
		final String declared;
		String title = "";
		long time = 0L;
		boolean secure = true;
		String scope = "";
		String post = "";
		long expiresAt = Long.MAX_VALUE;
		boolean taken = false;

		public Item(byte[] content, @Nullable String declared) {
			this.content = content;
			this.declared = declared;
		}

		public Item titled(String title, long time) {
			this.title = title;
			this.time = time;
			return this;
		}

		public Item secure(boolean secure) {
			this.secure = secure;
			return this;
		}

		public Item from(String scope, String post) {
			this.scope = scope;
			this.post = post;
			return this;
		}

		public Item expiresAt(long expiresAt) {
			this.expiresAt = expiresAt;
			return this;
		}

		public Item taken() {
			this.taken = true;
			return this;
		}
	}

	static final class Shown {

		final String token;
		final byte[] data;
		final String type;
		final String title;
		final long time;
		final boolean secure;
		final String scope;
		final String post;
		final long expiresAt;
		volatile long startedAt;

		private Shown(String token, byte[] data, String type, Item item) {
			this.token = token;
			this.data = data;
			this.type = type;
			this.title = item.title;
			this.time = item.time;
			this.secure = item.secure;
			this.scope = item.scope;
			this.post = item.post;
			this.expiresAt = item.expiresAt;
		}
	}

	private MediaViewer() {
	}

	public static String groupScope(byte[] groupId) {
		return "group:" + toHexString(groupId);
	}

	public static String channelScope(byte[] channelId) {
		return "channel:" + toHexString(channelId);
	}

	public static String groupPost(byte[] groupId, String postKey) {
		return groupScope(groupId) + "/" + postKey;
	}

	public static String channelPost(byte[] channelId, long seqNum) {
		return channelScope(channelId) + "/" + seqNum;
	}

	@Nullable
	public static String imageType(@Nullable String declared, byte[] content) {
		String type = ExternalViewerTypes.typeForContent(
				declared == null || declared.isEmpty() ? "image/jpeg"
						: declared, content);
		if (type == null || !type.startsWith("image/")) return null;
		return SafeImageDecoder.hasAllowedMagic(content) ? type : null;
	}

	@Nullable
	public static String videoType(@Nullable String declared, byte[] content) {
		String type = ExternalViewerTypes.typeForContent(
				declared == null || declared.isEmpty() ? "video/mp4"
						: declared, content);
		if (type == null) return null;
		for (String playable : PLAYABLE_VIDEO_TYPES) {
			if (playable.equals(type.toLowerCase(Locale.ROOT))) return type;
		}
		return null;
	}

	public static boolean showImage(Activity from, Executor io, Item item) {
		String type = imageType(item.declared, item.content);
		if (type == null || expired(item.expiresAt) || removed(item)) {
			return false;
		}
		open(from, io, ImageActivity.class, item, type);
		return true;
	}

	public static boolean playVideo(Activity from, Executor io, Item item) {
		String type = videoType(item.declared, item.content);
		if (type == null || expired(item.expiresAt) || removed(item)) {
			return false;
		}
		open(from, io, VideoPlayerActivity.class, item, type);
		return true;
	}

	private static void open(Activity from, Executor io, Class<?> screen,
			Item item, String type) {
		watchScreens(from.getApplication());
		String post = item.post;
		Shown again = null;
		long opened;
		boolean opening = false;
		boolean showAgain = false;
		synchronized (LOCK) {
			opened = generation;
			if (!post.isEmpty()) {
				for (Shown s : SHOWN.values()) {
					if (s.post.equals(post)) again = s;
				}
				if (again == null) opening = OPENING.add(post);
			}
			if (again != null) {
				long now = SystemClock.uptimeMillis();
				showAgain = !WATCHERS.containsKey(again.token)
						&& now - again.startedAt >= START_GRACE_MS;
				if (showAgain) again.startedAt = now;
			}
		}
		boolean duplicate = !post.isEmpty() && (again != null || !opening);
		if (duplicate && item.taken) {
			byte[] unused = item.content;
			wiper.execute(() -> wipe(unused));
		}
		if (again != null && showAgain) start(from, screen, again);
		if (duplicate) return;
		io.execute(() -> {
			byte[] data;
			try {
				data = item.taken ? item.content : copier.apply(item.content);
			} catch (OutOfMemoryError e) {
				data = null;
			}
			boolean noMemory = data == null;
			Shown shown = noMemory ? null : keep(item, type, data, opened);
			from.runOnUiThread(() -> {
				synchronized (LOCK) {
					OPENING.remove(post);
				}
				if (noMemory) {
					Toast.makeText(from, R.string.media_viewer_no_memory,
							Toast.LENGTH_LONG).show();
				} else if (shown == null || get(shown.token) == null) {
					Toast.makeText(from, screen == VideoPlayerActivity.class
							? R.string.video_playback_error
							: R.string.grouptr_attach_read_failed,
							Toast.LENGTH_SHORT).show();
				} else if (from.isFinishing() || from.isDestroyed()) {
					close(shown.token);
				} else {
					start(from, screen, shown);
				}
			});
		});
	}

	private static boolean expired(long expiresAt) {
		return clock.getAsLong() >= expiresAt;
	}

	@Nullable
	private static Shown keep(Item item, String type, byte[] data,
			long opened) {
		Shown shown = new Shown(newToken(), data, type, item);
		shown.startedAt = SystemClock.uptimeMillis();
		List<String> oldest = new ArrayList<>();
		boolean late;
		synchronized (LOCK) {
			late = opened != generation || removedLocked(item);
			if (!late) SHOWN.put(shown.token, shown);
		}
		if (late) {
			wiper.execute(() -> wipe(data));
			return null;
		}
		synchronized (LOCK) {
			int over = SHOWN.size() - MAX_SHOWN;
			for (String token : SHOWN.keySet()) {
				if (over-- <= 0) break;
				oldest.add(token);
			}
		}
		if (!oldest.isEmpty()) drop(s -> oldest.contains(s.token));
		if (shown.expiresAt != Long.MAX_VALUE) {
			String token = shown.token;
			Runnable timer = () -> drop(s -> s.token.equals(token));
			synchronized (LOCK) {
				if (SHOWN.containsKey(token)) TIMERS.put(token, timer);
				else timer = null;
			}
			if (timer != null) {
				MAIN.postDelayed(timer, Math.max(0L,
						shown.expiresAt - clock.getAsLong()));
			}
		}
		return shown;
	}

	private static void start(Activity from, Class<?> screen, Shown shown) {
		from.startActivity(new Intent(from, screen)
				.putExtra(EXTRA_TOKEN, shown.token));
	}

	@Nullable
	static Shown get(@Nullable String token) {
		if (token == null) return null;
		sweepExpired();
		synchronized (LOCK) {
			return SHOWN.get(token);
		}
	}

	static void sweepExpired() {
		drop(s -> expired(s.expiresAt));
	}

	private static void watchScreens(Application app) {
		synchronized (LOCK) {
			if (watched != null && watched.get() == app) return;
			watched = new WeakReference<>(app);
		}
		app.registerActivityLifecycleCallbacks(new Sweeper());
	}

	private static final class Sweeper
			implements Application.ActivityLifecycleCallbacks {

		@Override
		public void onActivityStarted(Activity activity) {
			sweepExpired();
		}

		@Override
		public void onActivityCreated(Activity activity,
				@Nullable Bundle savedInstanceState) {
		}

		@Override
		public void onActivityResumed(Activity activity) {
		}

		@Override
		public void onActivityPaused(Activity activity) {
		}

		@Override
		public void onActivityStopped(Activity activity) {
		}

		@Override
		public void onActivitySaveInstanceState(Activity activity,
				Bundle outState) {
		}

		@Override
		public void onActivityDestroyed(Activity activity) {
		}
	}

	public static boolean holds(@Nullable String token) {
		return get(token) != null;
	}

	public static boolean secure(@Nullable String token) {
		Shown shown = get(token);
		return shown == null || shown.secure;
	}

	static boolean watch(String token, Runnable gone) {
		if (get(token) == null) return false;
		synchronized (LOCK) {
			if (!SHOWN.containsKey(token)) return false;
			WATCHERS.put(token, gone);
			return true;
		}
	}

	static void unwatch(@Nullable String token) {
		if (token == null) return;
		synchronized (LOCK) {
			WATCHERS.remove(token);
		}
	}

	static void close(@Nullable String token) {
		if (token == null) return;
		unwatch(token);
		drop(s -> s.token.equals(token));
	}

	public static void forget(String scope) {
		synchronized (LOCK) {
			remember(scope);
		}
		drop(s -> s.scope.equals(scope));
	}

	public static void forgetPosts(Collection<String> posts) {
		if (posts.isEmpty()) return;
		synchronized (LOCK) {
			for (String post : posts) remember(post);
		}
		drop(s -> !s.post.isEmpty() && posts.contains(s.post));
	}

	public static void forgetRemoval(String scope) {
		String posts = scope + "/";
		synchronized (LOCK) {
			REMOVED.keySet().removeIf(key -> key.equals(scope)
					|| key.startsWith(posts));
		}
	}

	public static void forgetPostRemoval(String post) {
		synchronized (LOCK) {
			REMOVED.remove(post);
		}
	}

	public static void clearAll() {
		synchronized (LOCK) {
			generation++;
			OPENING.clear();
			REMOVED.clear();
		}
		drop(s -> true);
	}

	private static boolean removed(Item item) {
		synchronized (LOCK) {
			return removedLocked(item);
		}
	}

	private static boolean removedLocked(Item item) {
		long now = SystemClock.uptimeMillis();
		Iterator<Long> at = REMOVED.values().iterator();
		while (at.hasNext() && now - at.next() > REMOVED_FOR_MS) at.remove();
		return (!item.post.isEmpty() && REMOVED.containsKey(item.post))
				|| (!item.scope.isEmpty() && REMOVED.containsKey(item.scope));
	}

	private static void remember(String key) {
		if (key.isEmpty()) return;
		REMOVED.remove(key);
		REMOVED.put(key, SystemClock.uptimeMillis());
		Iterator<String> eldest = REMOVED.keySet().iterator();
		while (REMOVED.size() > MAX_REMOVED && eldest.hasNext()) {
			eldest.next();
			eldest.remove();
		}
	}

	private static void drop(Predicate<Shown> which) {
		List<Shown> gone = new ArrayList<>();
		List<Runnable> told = new ArrayList<>();
		List<Runnable> timers = new ArrayList<>();
		synchronized (LOCK) {
			Iterator<Shown> it = SHOWN.values().iterator();
			while (it.hasNext()) {
				Shown shown = it.next();
				if (!which.test(shown)) continue;
				it.remove();
				gone.add(shown);
				Runnable watcher = WATCHERS.remove(shown.token);
				if (watcher != null) told.add(watcher);
				Runnable timer = TIMERS.remove(shown.token);
				if (timer != null) timers.add(timer);
			}
		}
		if (gone.isEmpty()) return;
		wiper.execute(() -> {
			for (Shown shown : gone) wipe(shown.data);
		});
		for (Runnable timer : timers) MAIN.removeCallbacks(timer);
		for (Runnable watcher : told) MAIN.post(watcher);
	}

	private static void wipe(byte[] data) {
		Arrays.fill(data, (byte) 0);
	}

	@VisibleForTesting
	static boolean lockHeld() {
		return Thread.holdsLock(LOCK);
	}

	static int timers() {
		synchronized (LOCK) {
			return TIMERS.size();
		}
	}

	static int size() {
		synchronized (LOCK) {
			return SHOWN.size();
		}
	}

	private static String newToken() {
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		StringBuilder sb = new StringBuilder(32);
		for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
		return sb.toString();
	}
}
