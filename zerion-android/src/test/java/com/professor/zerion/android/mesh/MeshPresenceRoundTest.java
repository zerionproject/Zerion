package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MeshPresenceRoundTest {

	private static final int ROUND = 8;

	private final PluginManager pluginManager = mock(PluginManager.class);
	private final MeshController meshController = mock(MeshController.class);
	private final MeshManager meshManager = mock(MeshManager.class);
	private final MeshBundleStore bundleStore = mock(MeshBundleStore.class);
	private final MessagingManager messagingManager =
			mock(MessagingManager.class);
	private final ContactManager contactManager = mock(ContactManager.class);
	private final CryptoComponent crypto = mock(CryptoComponent.class);
	private final Map<AsyncPrekeyBundle, Integer> bundleOwner = new HashMap<>();
	private final List<String> sends =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void setUp() throws Exception {
		when(meshManager.isRunning()).thenReturn(true);
		when(meshManager.getPeerCount()).thenReturn(1);
		when(pluginManager.isOfflineMode()).thenReturn(true);
		doAnswer(inv -> {
			sends.add("real:" + bundleOwner.get(
					(AsyncPrekeyBundle) inv.getArgument(0)));
			return null;
		}).when(meshManager).sendOffline(any(),
				eq(MeshMessageRouter.MESH_PRESENCE), any(), anyLong(),
				anyBoolean());
		doAnswer(inv -> {
			sends.add("cover");
			return null;
		}).when(meshManager).sendCover(anyBoolean(), anyLong());
	}

	@Test
	public void everyRoundHasTheSameSizeWhateverTheNumberOfContacts()
			throws Exception {
		for (int contacts : new int[] {0, 1, 5, 9, 20}) {
			MeshTextSender sender = sender(contacts, true);
			sends.clear();
			round(sender);
			assertEquals("frames in a round with " + contacts + " contacts",
					ROUND, sends.size());
		}
	}

	@Test
	public void theRealBeaconIsNotAlwaysTheFirstFrame() throws Exception {
		MeshTextSender sender = sender(1, true);
		Set<Integer> positions = new HashSet<>();
		for (int i = 0; i < 30; i++) {
			sends.clear();
			round(sender);
			positions.add(sends.indexOf("real:1"));
		}
		assertTrue("positions of the real beacon: " + positions,
				positions.size() > 1);
	}

	@Test
	public void connectingAgainAndAgainDoesNotMultiplyRounds()
			throws Exception {
		ArgumentCaptor<Runnable> listener =
				ArgumentCaptor.forClass(Runnable.class);
		sender(1, false);
		verify(meshManager).setPeerConnectedListener(listener.capture());
		for (int i = 0; i < 10; i++) listener.getValue().run();
		Thread.sleep(5_500);
		assertTrue("frames sent: " + sends.size(), sends.size() <= 2 * ROUND);
	}

	private MeshTextSender sender(int contacts, boolean quiet)
			throws Exception {
		List<Contact> list = new ArrayList<>();
		for (int i = 1; i <= contacts; i++) {
			ContactId id = new ContactId(i);
			list.add(new Contact(id, TestUtils.getAuthor(),
					new AuthorId(TestUtils.getRandomId()), null, null, true,
					true, false, null));
			AsyncPrekeyBundle bundle = mock(AsyncPrekeyBundle.class);
			bundleOwner.put(bundle, i);
			when(bundleStore.getContactBundle(eq(i), any()))
					.thenReturn(bundle);
		}
		when(contactManager.getContacts()).thenReturn(list);
		MeshTextSender sender = new MeshTextSender(pluginManager,
				meshController, meshManager, bundleStore, messagingManager,
				new MeshOutbox(), contactManager, crypto, Runnable::run);
		if (quiet) {
			Field f = MeshTextSender.class.getDeclaredField("scheduler");
			f.setAccessible(true);
			((ScheduledExecutorService) f.get(sender)).shutdownNow();
		}
		return sender;
	}

	private static void round(MeshTextSender sender) throws Exception {
		Method m = MeshTextSender.class.getDeclaredMethod("broadcastPresence");
		m.setAccessible(true);
		m.invoke(sender);
	}
}
