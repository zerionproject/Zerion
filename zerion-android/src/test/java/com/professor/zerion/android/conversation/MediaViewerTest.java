package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.conversation.voice.VoiceChunkAssembler;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.CacheSweeper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.event.ChannelPostsRemovedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.util.StringUtils.toHexString;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MediaViewerTest {

	static {
		TestAndroidKeyStore.register();
	}

	static final byte[] MARKER = "decrypted group picture 0123456789"
			.getBytes(StandardCharsets.US_ASCII);

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@org.junit.Before
	public void setUp() {
		MediaViewer.wiper = Runnable::run;
	}

	@After
	public void tearDown() {
		MediaViewer.clearAll();
		MediaViewer.wiper = MediaViewer.DEFAULT_WIPER;
	}

	static byte[] jpeg() {
		return TestPictures.png(MARKER);
	}

	static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	static byte[] box(String type, byte[] body) {
		int n = body.length + 8;
		return concat(new byte[] {(byte) (n >> 24), (byte) (n >> 16),
				(byte) (n >> 8), (byte) n},
				type.getBytes(StandardCharsets.US_ASCII), body);
	}

	static byte[] mp4() {
		byte[] samples = new byte[200];
		System.arraycopy(MARKER, 0, samples, 10, MARKER.length);
		return concat(box("ftyp", "isom\0\0\2\0isommp41"
						.getBytes(StandardCharsets.ISO_8859_1)),
				box("moov", box("mvhd", new byte[100])),
				box("mdat", samples));
	}

	static MediaViewer.Item picture(String post) {
		return new MediaViewer.Item(jpeg(), "image/png")
				.titled("Alice", 1000L).secure(false)
				.from("group:aa", post);
	}

	private static Activity screen() {
		return Robolectric.buildActivity(Activity.class).setup().get();
	}

	private static String tokenOf(Intent i) {
		return i.getStringExtra(MediaViewer.EXTRA_TOKEN);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void filesHolding(File f, byte[] needle, List<String> out)
			throws Exception {
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children == null) return;
			for (File c : children) filesHolding(c, needle, out);
		} else if (f.isFile() && f.length() < 64 * 1024 * 1024) {
			byte[] bytes;
			try {
				bytes = Files.readAllBytes(f.toPath());
			} catch (NoSuchFileException removed) {
				return;
			}
			String content = new String(bytes, StandardCharsets.ISO_8859_1);
			if (content.contains(new String(needle,
					StandardCharsets.ISO_8859_1))) {
				out.add(f.getPath());
			}
		}
	}

	static List<String> storageHolding(Context ctx, byte[] needle)
			throws Exception {
		List<String> out = new ArrayList<>();
		filesHolding(new File(ctx.getApplicationInfo().dataDir), needle, out);
		filesHolding(ctx.getCacheDir(), needle, out);
		return out;
	}

	private static String open(Activity a, MediaViewer.Item item) {
		assertTrue(MediaViewer.showImage(a, Runnable::run, item));
		return tokenOf(shadowOf(a).getNextStartedActivity());
	}

	@Test
	public void aPictureGoesToTheViewerByAnOpaqueTokenAndIsNeverWritten()
			throws Exception {
		Activity a = screen();
		assertTrue(MediaViewer.showImage(a, Runnable::run,
				picture("group:aa/1")));
		Intent started = shadowOf(a).getNextStartedActivity();
		assertNotNull(started);
		assertEquals(ImageActivity.class.getName(),
				started.getComponent().getClassName());
		assertEquals("only a token is put in the intent",
				Arrays.asList(MediaViewer.EXTRA_TOKEN),
				new ArrayList<>(started.getExtras().keySet()));
		String token = tokenOf(started);
		assertTrue(token, token.matches("[0-9a-f]{32}"));
		assertEquals(Arrays.asList(), storageHolding(ctx, MARKER));
		MediaViewer.Shown shown = MediaViewer.get(token);
		assertNotNull(shown);
		assertEquals("image/png", shown.type);
		assertEquals("Alice", shown.title);
	}

	@Test
	public void aVideoGoesToTheInAppPlayer() {
		Activity a = screen();
		assertTrue(MediaViewer.playVideo(a, Runnable::run,
				new MediaViewer.Item(mp4(), "video/mp4").secure(true)
						.from("group:aa", "group:aa/2")));
		Intent started = shadowOf(a).getNextStartedActivity();
		assertEquals(VideoPlayerActivity.class.getName(),
				started.getComponent().getClassName());
		assertTrue(MediaViewer.secure(tokenOf(started)));
	}

	@Test
	public void contentThatIsNotAPictureOrAVideoOpensNothing() {
		Activity a = screen();
		byte[] playlist = ("#EXTM3U\n#EXTINF:10,clip\n"
				+ "http://198.51.100.7/clip.mp4\n")
				.getBytes(StandardCharsets.UTF_8);
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
				.getBytes(StandardCharsets.UTF_8);
		assertFalse(MediaViewer.playVideo(a, Runnable::run,
				new MediaViewer.Item(playlist, "video/mp4")));
		assertFalse(MediaViewer.showImage(a, Runnable::run,
				new MediaViewer.Item(playlist, "image/png")));
		assertFalse(MediaViewer.showImage(a, Runnable::run,
				new MediaViewer.Item(svg, "image/svg+xml")));
		assertFalse(MediaViewer.showImage(a, Runnable::run,
				new MediaViewer.Item(mp4(), "image/png")));
		assertNull(shadowOf(a).getNextStartedActivity());
		assertEquals(0, MediaViewer.size());
	}

	@Test
	public void theViewerKeepsItsOwnCopyAndClosingWipesOnlyThat() {
		Activity a = screen();
		byte[] picture = jpeg();
		String token = open(a, new MediaViewer.Item(picture, null));
		MediaViewer.Shown shown = MediaViewer.get(token);
		assertNotNull(shown);
		MediaViewer.close(token);
		assertNull(MediaViewer.get(token));
		assertArrayEquals("the closed copy still holds the picture",
				new byte[shown.data.length], shown.data);
		assertArrayEquals("the post's own bytes were wiped", jpeg(),
				picture);
	}

	@Test
	public void lockingAndSigningOutWipeEveryShownCopy() {
		Activity a = screen();
		MediaViewer.Shown one = MediaViewer.get(open(a, picture("group:aa/1")));
		assertTrue(MediaViewer.playVideo(a, Runnable::run,
				new MediaViewer.Item(mp4(), null)
						.from("channel:bb", "channel:bb/1")));
		MediaViewer.Shown two = MediaViewer.get(
				tokenOf(shadowOf(a).getNextStartedActivity()));
		assertNotNull(one);
		assertNotNull(two);
		CacheSweeper.sweep(ctx);
		assertEquals(0, MediaViewer.size());
		assertArrayEquals(new byte[one.data.length], one.data);
		assertArrayEquals(new byte[two.data.length], two.data);
	}

	@Test
	public void removingAGroupOrLeavingAChannelWipesOnlyItsCopies() {
		Activity a = screen();
		byte[] group = new byte[32];
		byte[] other = new byte[32];
		other[0] = 1;
		byte[] channel = new byte[32];
		channel[0] = 2;
		String gone = open(a, new MediaViewer.Item(jpeg(), null)
				.from(MediaViewer.groupScope(group), ""));
		String kept = open(a, new MediaViewer.Item(jpeg(), null)
				.from(MediaViewer.groupScope(other), ""));
		String left = open(a, new MediaViewer.Item(jpeg(), null)
				.from(MediaViewer.channelScope(channel), ""));
		DeletedContentEvictor evictor = new DeletedContentEvictor(
				new VoiceChunkAssembler(), () -> {
				});
		evictor.eventOccurred(new GroupTrLocalStateChangedEvent(group,
				GroupTrLocalStateChangedEvent.Kind.UPDATED));
		assertNotNull(MediaViewer.get(gone));
		evictor.eventOccurred(new GroupTrLocalStateChangedEvent(group,
				GroupTrLocalStateChangedEvent.Kind.REMOVED));
		assertNull(MediaViewer.get(gone));
		assertNotNull(MediaViewer.get(kept));
		assertNotNull(MediaViewer.get(left));
		evictor.eventOccurred(new ChannelStateChangedEvent(channel,
				ChannelStateChangedEvent.Kind.LEFT));
		assertNull(MediaViewer.get(left));
		assertNotNull(MediaViewer.get(kept));
	}

	@Test
	public void deletingAPostWipesItsCopyAndClosesItsViewer() {
		Activity a = screen();
		byte[] group = new byte[32];
		byte[] postA = new byte[32];
		postA[0] = 7;
		byte[] postB = new byte[32];
		postB[0] = 8;
		byte[] channel = new byte[32];
		channel[0] = 3;
		String a1 = open(a, new MediaViewer.Item(jpeg(), null).from(
				MediaViewer.groupScope(group),
				MediaViewer.groupPost(group, toHexString(postA))));
		String b1 = open(a, new MediaViewer.Item(jpeg(), null).from(
				MediaViewer.groupScope(group),
				MediaViewer.groupPost(group, toHexString(postB))));
		String c5 = open(a, new MediaViewer.Item(jpeg(), null).from(
				MediaViewer.channelScope(channel),
				MediaViewer.channelPost(channel, 5L)));
		MediaViewer.Shown held = MediaViewer.get(a1);
		assertNotNull(held);
		AtomicInteger closed = new AtomicInteger();
		assertTrue(MediaViewer.watch(a1, closed::incrementAndGet));
		assertTrue(MediaViewer.watch(c5, closed::incrementAndGet));
		DeletedContentEvictor evictor = new DeletedContentEvictor(
				new VoiceChunkAssembler(), () -> {
				});

		evictor.eventOccurred(new GroupTrPostsRemovedEvent(group,
				Collections.singletonList(postA), 0));
		assertNull("a deleted post can still be shown", MediaViewer.get(a1));
		assertArrayEquals(new byte[held.data.length], held.data);
		assertNotNull(MediaViewer.get(b1));
		idle();
		assertEquals("the open viewer was not told", 1, closed.get());

		evictor.eventOccurred(new ChannelPostsRemovedEvent(channel,
				Arrays.asList(4L, 6L)));
		assertNotNull(MediaViewer.get(c5));
		evictor.eventOccurred(new ChannelPostsRemovedEvent(channel,
				Collections.singletonList(5L)));
		assertNull(MediaViewer.get(c5));
		idle();
		assertEquals(2, closed.get());
		assertNotNull(MediaViewer.get(b1));
	}

	@Test
	public void aCopyGoesWhenItsPostsTimerRunsOut() {
		Activity a = screen();
		long now = System.currentTimeMillis();
		String token = open(a, picture("group:aa/9")
				.expiresAt(now + 60_000L));
		MediaViewer.Shown held = MediaViewer.get(token);
		assertNotNull(held);
		AtomicInteger closed = new AtomicInteger();
		assertTrue(MediaViewer.watch(token, closed::incrementAndGet));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
		assertNotNull(MediaViewer.get(token));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
		idle();
		assertNull("an expired post can still be shown",
				MediaViewer.get(token));
		assertArrayEquals(new byte[held.data.length], held.data);
		assertEquals(1, closed.get());
	}

	@Test
	public void anExpiredCopyIsNeverHandedOutEvenBeforeItsTimerFires() {
		Activity a = screen();
		long now = System.currentTimeMillis();
		String token = open(a, picture("group:aa/3").expiresAt(now + 1000L));
		java.util.function.LongSupplier real = MediaViewer.clock;
		try {
			MediaViewer.clock = () -> now + 2000L;
			assertNull(MediaViewer.get(token));
			assertFalse(MediaViewer.holds(token));
		} finally {
			MediaViewer.clock = real;
		}
	}

	@Test
	public void anExpiredCopyGoesWhenAnyZerionScreenComesToTheFront() {
		Activity a = screen();
		long now = System.currentTimeMillis();
		String token = open(a, picture("group:aa/1").expiresAt(now + 1000L));
		AtomicInteger closed = new AtomicInteger();
		assertTrue(MediaViewer.watch(token, closed::incrementAndGet));
		java.util.function.LongSupplier real = MediaViewer.clock;
		try {
			MediaViewer.clock = () -> now + 5000L;
			assertEquals("the copy went before the phone woke", 1,
					MediaViewer.size());
			Robolectric.buildActivity(Activity.class).setup();
			assertEquals("the expired copy waited for its timer", 0,
					MediaViewer.size());
			idle();
			assertEquals(1, closed.get());
		} finally {
			MediaViewer.clock = real;
		}
	}

	@Test
	public void lookingUpAnyCopyWipesEveryExpiredOne() {
		Activity a = screen();
		long now = System.currentTimeMillis();
		open(a, picture("group:aa/1").expiresAt(now + 1000L));
		String lasting = open(a, picture("group:aa/2"));
		java.util.function.LongSupplier real = MediaViewer.clock;
		try {
			MediaViewer.clock = () -> now + 5000L;
			assertNotNull(MediaViewer.get(lasting));
			assertEquals(1, MediaViewer.size());
		} finally {
			MediaViewer.clock = real;
		}
	}

	@Test
	public void aPostThatHasAlreadyExpiredDoesNotOpen() {
		Activity a = screen();
		assertFalse(MediaViewer.showImage(a, Runnable::run, picture("group:aa/4")
				.expiresAt(System.currentTimeMillis() - 1L)));
		assertNull(shadowOf(a).getNextStartedActivity());
		assertEquals(0, MediaViewer.size());
	}

	@Test
	public void theCopyIsMadeAwayFromTheScreenThread() {
		Activity a = screen();
		List<Runnable> io = new ArrayList<>();
		byte[] picture = jpeg();
		AtomicInteger copies = new AtomicInteger();
		java.util.function.UnaryOperator<byte[]> real = MediaViewer.copier;
		MediaViewer.copier = data -> {
			copies.incrementAndGet();
			return real.apply(data);
		};
		try {
			assertTrue(MediaViewer.showImage(a, io::add,
					new MediaViewer.Item(picture, null)
							.from("group:aa", "group:aa/1")));
			assertEquals("the copy was made on the screen's thread", 0,
					copies.get());
			assertNull(shadowOf(a).getNextStartedActivity());
			assertEquals(1, io.size());
			io.get(0).run();
			idle();
			assertEquals(1, copies.get());
			assertNotNull(shadowOf(a).getNextStartedActivity());
		} finally {
			MediaViewer.copier = real;
		}
	}

	@Test
	public void aQuickSecondTapOpensOneViewerWithOneCopy() {
		Activity a = screen();
		List<Runnable> io = new ArrayList<>();
		MediaViewer.Item item = picture("group:aa/1");
		assertTrue(MediaViewer.showImage(a, io::add, item));
		assertTrue(MediaViewer.showImage(a, io::add, picture("group:aa/1")));
		assertEquals("a second copy was started", 1, io.size());
		io.get(0).run();
		idle();
		assertNotNull(shadowOf(a).getNextStartedActivity());
		assertTrue(MediaViewer.showImage(a, io::add, picture("group:aa/1")));
		assertEquals(1, io.size());
		assertNull("a second viewer opened", shadowOf(a)
				.getNextStartedActivity());
		assertEquals(1, MediaViewer.size());
	}

	@Test
	public void aCopyLeftWithoutAViewerIsShownAgainWithoutCopyingAgain() {
		Activity a = screen();
		String first = open(a, picture("group:aa/1"));
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertTrue(MediaViewer.showImage(a, Runnable::run,
				picture("group:aa/1")));
		Intent again = shadowOf(a).getNextStartedActivity();
		assertNotNull(again);
		assertEquals(first, tokenOf(again));
		assertEquals(1, MediaViewer.size());
	}

	@Test
	public void onlyAFewCopiesAreKeptAndTheOldestGoFirst() {
		Activity a = screen();
		List<String> tokens = new ArrayList<>();
		List<MediaViewer.Shown> held = new ArrayList<>();
		AtomicInteger closed = new AtomicInteger();
		for (int n = 0; n < MediaViewer.MAX_SHOWN + 2; n++) {
			String token = open(a, picture("group:aa/" + n));
			tokens.add(token);
			held.add(MediaViewer.get(token));
			assertTrue(MediaViewer.watch(token, closed::incrementAndGet));
		}
		idle();
		assertEquals(MediaViewer.MAX_SHOWN, MediaViewer.size());
		assertNull(MediaViewer.get(tokens.get(0)));
		assertNull(MediaViewer.get(tokens.get(1)));
		assertNotNull(MediaViewer.get(tokens.get(2)));
		assertArrayEquals(new byte[held.get(0).data.length],
				held.get(0).data);
		assertEquals("the closed viewers were not told", 2, closed.get());
	}

	@Test
	public void runningOutOfMemorySaysSoAndOpensNothing() {
		Activity a = screen();
		java.util.function.UnaryOperator<byte[]> real = MediaViewer.copier;
		MediaViewer.copier = data -> {
			throw new OutOfMemoryError();
		};
		try {
			assertTrue(MediaViewer.showImage(a, Runnable::run,
					picture("group:aa/1")));
		} finally {
			MediaViewer.copier = real;
		}
		idle();
		assertNull(shadowOf(a).getNextStartedActivity());
		assertEquals(0, MediaViewer.size());
		assertEquals(ctx.getString(com.professor.zerion.R.string
						.media_viewer_no_memory),
				org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
		assertTrue(MediaViewer.showImage(a, Runnable::run,
				picture("group:aa/1")));
		assertNotNull("the post stays blocked after the failure",
				shadowOf(a).getNextStartedActivity());
	}

	@Test
	public void contentTheCallerHandsOverIsKeptWithoutAnotherCopy() {
		Activity a = screen();
		byte[] fresh = jpeg();
		String token = open(a, new MediaViewer.Item(fresh, null).taken());
		MediaViewer.Shown shown = MediaViewer.get(token);
		assertNotNull(shown);
		assertTrue(shown.data == fresh);
	}

	private static java.lang.ref.WeakReference<byte[]> copyOf(String token) {
		return new java.lang.ref.WeakReference<>(MediaViewer.get(token).data);
	}

	private static boolean collected(java.lang.ref.WeakReference<?> ref)
			throws InterruptedException {
		for (int round = 0; round < 50 && ref.get() != null; round++) {
			System.gc();
			Thread.sleep(10);
		}
		return ref.get() == null;
	}

	@Test
	public void aCopyThatIsGoneIsNotKeptAliveByItsTimer() throws Exception {
		Activity a = screen();
		long week = 7L * 24L * 60L * 60L * 1000L;
		long now = System.currentTimeMillis();
		String closed = open(a, picture("group:aa/1").expiresAt(now + week));
		String forgotten = open(a, picture("group:aa/2")
				.expiresAt(now + week));
		String locked = open(a, picture("group:aa/3").expiresAt(now + week));
		java.lang.ref.WeakReference<byte[]> one = copyOf(closed);
		java.lang.ref.WeakReference<byte[]> two = copyOf(forgotten);
		java.lang.ref.WeakReference<byte[]> three = copyOf(locked);

		MediaViewer.close(closed);
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/2"));
		MediaViewer.clearAll();
		assertTrue("a closed copy is still held in memory", collected(one));
		assertTrue("a forgotten copy is still held in memory",
				collected(two));
		assertTrue("a copy wiped at lock is still held in memory",
				collected(three));
	}

	@Test
	public void eachTimedCopyHasOneTimerThatGoesWithIt() {
		Activity a = screen();
		long later = System.currentTimeMillis() + 60L * 60L * 1000L;
		String closed = open(a, picture("group:aa/1").expiresAt(later));
		open(a, picture("group:aa/2").expiresAt(later));
		open(a, picture("group:aa/3"));
		assertEquals(2, MediaViewer.timers());
		MediaViewer.close(closed);
		assertEquals("a timer outlives its copy", 1, MediaViewer.timers());
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/2"));
		assertEquals(0, MediaViewer.timers());
	}

	@Test
	public void aPostDeletedWhileItsCopyIsMadeIsNeverShown() {
		Activity a = screen();
		List<Runnable> io = new ArrayList<>();
		assertTrue(MediaViewer.showImage(a, io::add, picture("group:aa/1")));
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/1"));
		for (Runnable r : io) r.run();
		idle();
		assertNull("a deleted post was opened",
				shadowOf(a).getNextStartedActivity());
		assertEquals(0, MediaViewer.size());
		assertTold(com.professor.zerion.R.string.grouptr_attach_read_failed);
	}

	private void assertTold(int message) {
		assertEquals("the refusal was silent", ctx.getString(message),
				org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void aVideoWhosePostGoesWhileItsCopyIsMadeSaysItCannotPlay() {
		Activity a = screen();
		List<Runnable> io = new ArrayList<>();
		assertTrue(MediaViewer.playVideo(a, io::add,
				new MediaViewer.Item(mp4(), "video/mp4")
						.from("group:aa", "group:aa/1")));
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/1"));
		for (Runnable r : io) r.run();
		idle();
		assertNull(shadowOf(a).getNextStartedActivity());
		assertTold(com.professor.zerion.R.string.video_playback_error);
	}

	@Test
	public void aGroupRemovedWhileACopyIsMadeShowsNothing() {
		Activity a = screen();
		List<Runnable> io = new ArrayList<>();
		assertTrue(MediaViewer.showImage(a, io::add, picture("group:aa/1")));
		MediaViewer.forget("group:aa");
		for (Runnable r : io) r.run();
		idle();
		assertNull(shadowOf(a).getNextStartedActivity());
		assertEquals(0, MediaViewer.size());
	}

	@Test
	public void lockingWhileACopyIsMadeShowsNothing() {
		Activity a = screen();
		List<Runnable> io = new ArrayList<>();
		assertTrue(MediaViewer.showImage(a, io::add, picture("group:aa/1")));
		MediaViewer.clearAll();
		for (Runnable r : io) r.run();
		idle();
		assertNull("a copy made across a lock was shown",
				shadowOf(a).getNextStartedActivity());
		assertEquals(0, MediaViewer.size());
		assertTold(com.professor.zerion.R.string.grouptr_attach_read_failed);
	}

	@Test
	public void aTapOnAPostAlreadyDeletedOpensNothing() {
		Activity a = screen();
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/1"));
		List<Runnable> io = new ArrayList<>();
		assertFalse("a deleted post can be tapped open",
				MediaViewer.showImage(a, io::add, picture("group:aa/1")));
		assertTrue(io.isEmpty());
		assertNull(shadowOf(a).getNextStartedActivity());
		assertTrue(MediaViewer.showImage(a, Runnable::run,
				picture("group:aa/2")));
		assertNotNull(shadowOf(a).getNextStartedActivity());
	}

	private static DeletedContentEvictor evictor() {
		return new DeletedContentEvictor(new VoiceChunkAssembler(), () -> {
		});
	}

	private static MediaViewer.Item from(String scope, String post) {
		return new MediaViewer.Item(jpeg(), "image/png").from(scope, post);
	}

	@Test
	public void rejoiningAChannelOrAGroupLetsItsPicturesOpenAgain() {
		Activity a = screen();
		byte[] channel = new byte[32];
		channel[0] = 4;
		byte[] group = new byte[32];
		group[0] = 5;
		String channelScope = MediaViewer.channelScope(channel);
		String groupScope = MediaViewer.groupScope(group);
		DeletedContentEvictor evictor = evictor();
		evictor.eventOccurred(new ChannelStateChangedEvent(channel,
				ChannelStateChangedEvent.Kind.LEFT));
		evictor.eventOccurred(new GroupTrLocalStateChangedEvent(group,
				GroupTrLocalStateChangedEvent.Kind.REMOVED));
		assertFalse(MediaViewer.showImage(a, Runnable::run, from(channelScope,
				MediaViewer.channelPost(channel, 1L))));
		assertFalse(MediaViewer.showImage(a, Runnable::run, from(groupScope,
				MediaViewer.groupPost(group, "aa"))));

		evictor.eventOccurred(new ChannelStateChangedEvent(channel,
				ChannelStateChangedEvent.Kind.JOINED));
		evictor.eventOccurred(new GroupTrLocalStateChangedEvent(group,
				GroupTrLocalStateChangedEvent.Kind.CREATED));
		assertTrue("a rejoined channel's pictures stay refused",
				MediaViewer.showImage(a, Runnable::run, from(channelScope,
						MediaViewer.channelPost(channel, 1L))));
		assertTrue("a group joined again keeps refusing its pictures",
				MediaViewer.showImage(a, Runnable::run, from(groupScope,
						MediaViewer.groupPost(group, "aa"))));
	}

	@Test
	public void addingAMemberDoesNotForgetADeletedPost() {
		Activity a = screen();
		byte[] group = new byte[32];
		group[0] = 7;
		String scope = MediaViewer.groupScope(group);
		byte[] deleted = new byte[32];
		deleted[0] = (byte) 0xbb;
		String post = MediaViewer.groupPost(group, toHexString(deleted));
		DeletedContentEvictor evictor = evictor();
		evictor.eventOccurred(new GroupTrPostsRemovedEvent(group,
				Collections.singletonList(deleted), 0));
		evictor.eventOccurred(new GroupTrLocalStateChangedEvent(group,
				GroupTrLocalStateChangedEvent.Kind.MEMBER_ADDED));
		assertFalse("an added member reopened a deleted post",
				MediaViewer.showImage(a, Runnable::run, from(scope, post)));
		assertNull(shadowOf(a).getNextStartedActivity());
	}

	@Test
	public void aChannelPostAcceptedAgainOpensAgain() {
		Activity a = screen();
		byte[] channel = new byte[32];
		channel[0] = 6;
		String scope = MediaViewer.channelScope(channel);
		DeletedContentEvictor evictor = evictor();
		evictor.eventOccurred(new ChannelPostsRemovedEvent(channel,
				Arrays.asList(5L, 6L)));
		assertFalse(MediaViewer.showImage(a, Runnable::run,
				from(scope, MediaViewer.channelPost(channel, 5L))));
		evictor.eventOccurred(new org.zerionproject.app.api.channel.event
				.ChannelPostReceivedEvent(channel, 5L, false));
		assertTrue("a post that came back stays refused",
				MediaViewer.showImage(a, Runnable::run,
						from(scope, MediaViewer.channelPost(channel, 5L))));
		assertFalse(MediaViewer.showImage(a, Runnable::run,
				from(scope, MediaViewer.channelPost(channel, 6L))));
	}

	@Test
	public void aDeleteJustBeforeTheViewerStartsOpensNothing()
			throws Exception {
		Activity a = screen();
		List<Throwable> failed = new ArrayList<>();
		java.util.concurrent.Executor elsewhere = r -> {
			Thread t = new Thread(() -> {
				try {
					r.run();
				} catch (Throwable e) {
					failed.add(e);
				}
			});
			t.start();
			try {
				t.join();
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		};
		assertTrue(MediaViewer.showImage(a, elsewhere,
				picture("group:aa/1")));
		assertEquals(String.valueOf(failed), 0, failed.size());
		assertEquals(1, MediaViewer.size());
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/1"));
		idle();
		assertNull("a viewer opened on a deleted post",
				shadowOf(a).getNextStartedActivity());
		assertTold(com.professor.zerion.R.string.grouptr_attach_read_failed);
	}

	@Test
	public void wipingACopyNeverHoldsTheViewerLock() {
		List<Boolean> held = new ArrayList<>();
		MediaViewer.wiper = r -> {
			held.add(MediaViewer.lockHeld());
			r.run();
		};
		Activity a = screen();
		MediaViewer.Shown closed = MediaViewer.get(open(a,
				picture("group:aa/1")));
		MediaViewer.Shown forgotten = MediaViewer.get(open(a,
				picture("group:aa/2")));
		MediaViewer.Shown locked = MediaViewer.get(open(a,
				picture("group:aa/3")));
		assertNotNull(closed);
		assertNotNull(forgotten);
		assertNotNull(locked);
		MediaViewer.close(closed.token);
		MediaViewer.forgetPosts(Collections.singletonList("group:aa/2"));
		MediaViewer.clearAll();
		assertFalse("nothing was wiped", held.isEmpty());
		assertFalse("a copy was wiped while the lock was held",
				held.contains(true));
		assertArrayEquals(new byte[closed.data.length], closed.data);
		assertArrayEquals(new byte[forgotten.data.length], forgotten.data);
		assertArrayEquals(new byte[locked.data.length], locked.data);
	}

	@Test
	public void aWipeAskedForOnTheScreenThreadIsDoneElsewhereAtOnce()
			throws Exception {
		java.util.concurrent.atomic.AtomicReference<Thread> wipedOn =
				new java.util.concurrent.atomic.AtomicReference<>();
		MediaViewer.wiper = r -> MediaViewer.DEFAULT_WIPER.execute(() -> {
			wipedOn.set(Thread.currentThread());
			r.run();
		});
		Activity a = screen();
		MediaViewer.Shown shown = MediaViewer.get(open(a,
				picture("group:aa/1")));
		assertNotNull(shown);
		MediaViewer.close(shown.token);
		byte[] zeros = new byte[shown.data.length];
		long deadline = System.currentTimeMillis() + 5000L;
		while (!Arrays.equals(zeros, shown.data)
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(5);
		}
		assertArrayEquals("the copy was not wiped promptly", zeros,
				shown.data);
		assertNotNull(wipedOn.get());
		assertTrue("the screen thread did the wiping",
				wipedOn.get() != Thread.currentThread());
	}

	@Test
	public void aLockOffTheScreenThreadWipesBeforeItReturns()
			throws Exception {
		MediaViewer.wiper = MediaViewer.DEFAULT_WIPER;
		Activity a = screen();
		MediaViewer.Shown shown = MediaViewer.get(open(a,
				picture("group:aa/1")));
		assertNotNull(shown);
		java.util.concurrent.atomic.AtomicBoolean zeroed =
				new java.util.concurrent.atomic.AtomicBoolean();
		Thread sweep = new Thread(() -> {
			MediaViewer.clearAll();
			zeroed.set(Arrays.equals(new byte[shown.data.length],
					shown.data));
		});
		sweep.start();
		sweep.join();
		assertTrue("a lock returned before the copy was wiped",
				zeroed.get());
	}

	@Test
	public void anUnknownTokenCountsAsProtected() {
		assertTrue(MediaViewer.secure("00"));
		assertTrue(MediaViewer.secure(null));
		assertFalse(MediaViewer.watch("00", () -> {
		}));
	}
}
