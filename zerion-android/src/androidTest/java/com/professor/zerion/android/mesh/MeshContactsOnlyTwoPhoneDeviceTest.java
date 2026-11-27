package com.professor.zerion.android.mesh;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.zerionproject.transport.mesh.MeshForwarder;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

@RunWith(AndroidJUnit4.class)
public class MeshContactsOnlyTwoPhoneDeviceTest {

	private static final long LINK_WAIT_MS = 150_000L;
	private static final long FRAME_WAIT_MS = 60_000L;
	private static final long LINGER_MS = 20_000L;

	private static byte[] key(int fill) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) fill);
		return k;
	}

	@Test
	public void twoPhonesLinkAsContactsAndPassAFrameEachWay()
			throws Exception {
		Bundle args = InstrumentationRegistry.getArguments();
		String role = args.getString("role");
		assumeTrue("needs -e role a or b on two phones at once",
				"a".equals(role) || "b".equals(role));
		assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
		Context context = ApplicationProvider.getApplicationContext();
		BluetoothAdapter adapter = ((BluetoothManager) context
				.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
		assertTrue("Bluetooth is off", adapter != null && adapter.isEnabled());
		assertTrue("Bluetooth permissions are not granted",
				MeshController.hasPermissions(context));

		boolean first = "a".equals(role);
		byte[] own = key(first ? 0x11 : 0x22);
		byte[] theirs = key(first ? 0x22 : 0x11);
		byte[] linkKey = MeshPrivateLink.linkKey(key(0x33), key(0x44));
		List<MeshPeer> peers = Collections.singletonList(
				new MeshPeer(1, theirs, linkKey, first));
		String mine = "frame from " + role;
		String expected = "frame from " + (first ? "b" : "a");
		Set<String> received = ConcurrentHashMap.newKeySet();
		MeshForwarder forwarder = new MeshForwarder(payload ->
				received.add(new String(payload, StandardCharsets.UTF_8)),
				new SecureRandom());
		BleMeshTransport transport =
				new BleMeshTransport(context, forwarder, null, null);
		transport.setPrivatePeers(new BleMeshTransport.PrivatePeers() {
			@Override
			@Nullable
			public byte[] ownAdvertKey() {
				return own;
			}

			@Override
			public List<MeshPeer> peers() {
				return peers;
			}
		});
		transport.start();
		try {
			long until = System.currentTimeMillis() + LINK_WAIT_MS;
			while (transport.getPeerCount() < 1
					&& System.currentTimeMillis() < until) {
				Thread.sleep(250);
			}
			assertTrue("the two phones did not link as contacts",
					transport.getPeerCount() == 1);
			until = System.currentTimeMillis() + FRAME_WAIT_MS;
			while (!received.contains(expected)
					&& System.currentTimeMillis() < until) {
				if (transport.getPeerCount() == 1) {
					forwarder.originate(
							mine.getBytes(StandardCharsets.UTF_8));
				}
				Thread.sleep(2_000);
			}
			assertTrue("no frame from the other phone arrived; linked="
					+ transport.getPeerCount(), received.contains(expected));
			long linger = System.currentTimeMillis() + LINGER_MS;
			while (System.currentTimeMillis() < linger) {
				if (transport.getPeerCount() == 1) {
					forwarder.originate(
							mine.getBytes(StandardCharsets.UTF_8));
				}
				Thread.sleep(2_000);
			}
		} finally {
			transport.stop();
		}
	}
}
