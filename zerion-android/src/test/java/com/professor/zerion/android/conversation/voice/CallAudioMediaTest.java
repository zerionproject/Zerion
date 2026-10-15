package com.professor.zerion.android.conversation.voice;

import android.media.AudioTrack;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.shadows.ShadowAudioTrack;
import org.zerionproject.app.conversation.voice.VoiceCallCrypto;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = CallAudioMediaTest.CapturingTrack.class)
public class CallAudioMediaTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int SYNC = 0x5A455249;
	private static final int READY = -1;
	private static final int HEARTBEAT = -2;
	private static final int BYE = -3;
	private static final int FRAME = 640;
	private static final int CAPTURED_FRAMES = 5;

	private final CallRig rig = new CallRig("zt-media-call");
	private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
	private final Inbound inbound = new Inbound();
	private final AtomicInteger captured = new AtomicInteger();

	@Implements(AudioTrack.class)
	public static class CapturingTrack extends ShadowAudioTrack {

		static final ByteArrayOutputStream PLAYED =
				new ByteArrayOutputStream();

		@Implementation
		protected int write(byte[] audioData, int offsetInBytes,
				int sizeInBytes) {
			synchronized (PLAYED) {
				PLAYED.write(audioData, offsetInBytes, sizeInBytes);
			}
			return sizeInBytes;
		}
	}

	private static final class Inbound extends InputStream {

		private final LinkedBlockingQueue<Integer> bytes =
				new LinkedBlockingQueue<>();
		private volatile boolean closed;

		void feed(byte[] data) {
			for (byte b : data) bytes.add(b & 0xFF);
		}

		@Override
		public int read() throws IOException {
			while (true) {
				if (closed && bytes.isEmpty()) return -1;
				try {
					Integer b = bytes.poll(50, TimeUnit.MILLISECONDS);
					if (b != null) return b;
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
			}
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) return 0;
			int first = read();
			if (first < 0) return -1;
			b[off] = (byte) first;
			int n = 1;
			while (n < len && !bytes.isEmpty()) {
				Integer next = bytes.poll();
				if (next == null) break;
				b[off + n++] = (byte) next.intValue();
			}
			return n;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	@Before
	public void setUp() {
		synchronized (CapturingTrack.PLAYED) {
			CapturingTrack.PLAYED.reset();
		}
	}

	@After
	public void tearDown() {
		inbound.close();
		ShadowAudioRecord.clearSource();
		rig.tearDown();
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x3C);
		return new SecretKey(raw);
	}

	private static byte[] pcm(int frame) {
		byte[] b = new byte[FRAME];
		for (int i = 0; i < FRAME; i++) b[i] = (byte) (frame * 31 + i);
		return b;
	}

	private DuplexTransportConnection connection() {
		TransportConnectionReader reader = (TransportConnectionReader)
				Proxy.newProxyInstance(
						TransportConnectionReader.class.getClassLoader(),
						new Class<?>[] {TransportConnectionReader.class},
						(p, m, a) -> m.getName().equals("getInputStream")
								? inbound : null);
		TransportConnectionWriter writer = (TransportConnectionWriter)
				Proxy.newProxyInstance(
						TransportConnectionWriter.class.getClassLoader(),
						new Class<?>[] {TransportConnectionWriter.class},
						(p, m, a) -> {
							if (m.getName().equals("getOutputStream")) {
								return sent;
							}
							Class<?> r = m.getReturnType();
							if (r == int.class) return 0;
							if (r == long.class) return 0L;
							if (r == boolean.class) return false;
							return null;
						});
		return (DuplexTransportConnection) Proxy.newProxyInstance(
				DuplexTransportConnection.class.getClassLoader(),
				new Class<?>[] {DuplexTransportConnection.class},
				(p, m, a) -> {
					if (m.getName().equals("getReader")) return reader;
					if (m.getName().equals("getWriter")) return writer;
					return null;
				});
	}

	private void captureFiveFrames() {
		ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
			@Override
			public int readInByteArray(byte[] audioData, int offsetInBytes,
					int sizeInBytes, boolean isBlocking) {
				int frame = captured.get();
				if (frame >= CAPTURED_FRAMES) {
					try {
						Thread.sleep(5);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return 0;
				}
				byte[] src = pcm(frame);
				System.arraycopy(src, FRAME - sizeInBytes, audioData,
						offsetInBytes, sizeInBytes);
				captured.incrementAndGet();
				return sizeInBytes;
			}
		});
	}

	private VoiceCallService connected(boolean agreement) throws Exception {
		captureFiveFrames();
		VoiceCallService s = rig.ringingOutgoing();
		CallRig.set(s, "voiceCallKey", callKey());
		if (agreement) {
			CallRig.set(s, "agreementNegotiated", true);
			CallRig.set(s, "audioKeys", rig.crypto().deriveAudioKeys(
					callKey(), true));
		}
		Method m = VoiceCallService.class.getDeclaredMethod(
				"onEndpointConnection", DuplexTransportConnection.class);
		m.setAccessible(true);
		m.invoke(s, connection());
		CallRig.idle();
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
		return s;
	}

	private static byte[] frame(VoiceCallCrypto crypto, SecretKey key,
			boolean agreement, long seq, byte[] pcm, boolean breakCrc)
			throws IOException {
		byte[] ct = agreement
				? crypto.encryptAudioFrame(pcm, key,
						crypto.audioFrameAssociatedData(false, seq))
				: crypto.encryptAudioFrame(pcm, key);
		CRC32 crc = new CRC32();
		crc.update(ct, 0, ct.length);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(b);
		out.writeInt(ct.length);
		out.writeLong(seq);
		out.writeLong(0L);
		out.writeLong(crc.getValue() + (breakCrc ? 1 : 0));
		out.write(ct);
		return b.toByteArray();
	}

	private static byte[] ints(int... values) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(b);
		for (int v : values) out.writeInt(v);
		return b.toByteArray();
	}

	private static byte[] oversized(byte[] hidden) throws Exception {
		java.lang.reflect.Field f =
				VoiceCallService.class.getDeclaredField("BUFFER_SIZE");
		f.setAccessible(true);
		int length = (Integer) f.get(null) * 8 + 65;
		byte[] payload = new byte[length];
		System.arraycopy(hidden, 0, payload, 0, hidden.length);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(b);
		out.writeInt(length);
		out.writeLong(99L);
		out.writeLong(0L);
		out.writeLong(0L);
		out.write(payload);
		return b.toByteArray();
	}

	private byte[] sentSnapshot() {
		synchronized (sent) {
			return sent.toByteArray();
		}
	}

	private byte[] playedSnapshot() {
		synchronized (CapturingTrack.PLAYED) {
			return CapturingTrack.PLAYED.toByteArray();
		}
	}

	private void checkOutgoing(boolean agreement) throws Exception {
		int expected = 8 + CAPTURED_FRAMES * (28 + FRAME + 28);
		CallRig.await(() -> sentSnapshot().length >= expected, 15_000);
		VoiceCallCrypto crypto = rig.crypto();
		VoiceCallCrypto.AudioKeys peer =
				crypto.deriveAudioKeys(callKey(), false);
		DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(sentSnapshot()));
		assertEquals(SYNC, in.readInt());
		assertEquals(READY, in.readInt());
		for (int k = 0; k < CAPTURED_FRAMES; k++) {
			int len = in.readInt();
			assertEquals(k, in.readLong());
			assertEquals(0L, in.readLong());
			long crc = in.readLong();
			byte[] ct = new byte[len];
			in.readFully(ct);
			CRC32 c = new CRC32();
			c.update(ct, 0, ct.length);
			assertEquals(c.getValue(), crc);
			byte[] plain = agreement
					? crypto.decryptAudioFrame(ct, peer.rxKey,
							crypto.audioFrameAssociatedData(true, k))
					: crypto.decryptAudioFrame(ct, peer.rxKey);
			assertArrayEquals("frame " + k, pcm(k), plain);
		}
	}

	private void checkIncoming(VoiceCallService s, boolean agreement)
			throws Exception {
		VoiceCallCrypto crypto = rig.crypto();
		SecretKey peerTx = crypto.deriveAudioKeys(callKey(), false).txKey;
		inbound.feed(ints(SYNC));
		List<byte[]> expected = new ArrayList<>();
		for (int k = 0; k < 5; k++) {
			inbound.feed(frame(crypto, peerTx, agreement, k, pcm(100 + k),
					false));
			expected.add(pcm(100 + k));
		}
		inbound.feed(frame(crypto, peerTx, agreement, 5, pcm(105), true));
		inbound.feed(frame(crypto, peerTx, agreement, 4, pcm(104), false));
		inbound.feed(oversized(frame(crypto, peerTx, agreement, 7, pcm(107),
				false)));
		inbound.feed(frame(crypto, peerTx, agreement, 6, pcm(106), false));
		expected.add(pcm(106));
		inbound.feed(ints(-9, HEARTBEAT, READY));
		inbound.feed(frame(crypto, peerTx, agreement, 9, pcm(109), false));
		expected.add(pcm(109));
		ByteArrayOutputStream want = new ByteArrayOutputStream();
		for (byte[] e : expected) want.write(e);
		CallRig.await(() -> playedSnapshot().length >= want.size(), 15_000);
		Thread.sleep(300);
		assertArrayEquals(want.toByteArray(), playedSnapshot());

		inbound.feed(ints(BYE));
		CallRig.await(() -> s.getCallState()
				== VoiceCallService.CallState.DISCONNECTED, 15_000);
		assertEquals(VoiceCallService.CallState.DISCONNECTED,
				s.getCallState());
		CallRig.await(() -> {
			byte[] out = sentSnapshot();
			return out.length >= 4 && out[out.length - 1] == (byte) 0xFD;
		}, 15_000);
		byte[] out = sentSnapshot();
		assertArrayEquals("our side says bye on the media stream",
				ints(BYE), Arrays.copyOfRange(out, out.length - 4,
						out.length));
		CallRig.await(() -> rig.sentTypes().contains("createCallEnd"),
				15_000);
		assertTrue(rig.sentTypes().contains("createCallEnd"));
	}

	@Test
	public void legacyScheduleFramesAndPlaysAudio() throws Exception {
		VoiceCallService s = connected(false);
		checkOutgoing(false);
		checkIncoming(s, false);
	}

	@Test
	public void aFrameBeyondTheSkipLimitClosesTheMediaStream()
			throws Exception {
		VoiceCallService s = connected(false);
		VoiceCallCrypto crypto = rig.crypto();
		SecretKey peerTx = crypto.deriveAudioKeys(callKey(), false).txKey;
		inbound.feed(ints(SYNC));
		ByteArrayOutputStream want = new ByteArrayOutputStream();
		for (int k = 0; k < 3; k++) {
			inbound.feed(frame(crypto, peerTx, false, k, pcm(100 + k),
					false));
			want.write(pcm(100 + k));
		}
		CallRig.await(() -> playedSnapshot().length >= want.size(), 15_000);
		inbound.feed(ints(CallAudioWire.MAX_SKIPPED_PAYLOAD_BYTES + 1, 0, 3,
				0, 0, 0, 0));
		inbound.feed(frame(crypto, peerTx, false, 3, pcm(103), false));
		CallRig.await(() -> s.getCallState()
				!= VoiceCallService.CallState.CONNECTED, 15_000);
		assertTrue(s.getCallState() != VoiceCallService.CallState.CONNECTED);
		Thread.sleep(300);
		assertArrayEquals(want.toByteArray(), playedSnapshot());
	}

	@Test
	public void agreedScheduleSealsDirectionAndSequence() throws Exception {
		VoiceCallService s = connected(true);
		checkOutgoing(true);
		checkIncoming(s, true);
	}
}
