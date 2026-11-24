package com.professor.zerion.android.vault.ui;

import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.crypto.ProtectedNoteCodec;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultPasswordHandOverTest {

	private static final String PROTECTED_TITLE =
			ProtectedNoteCodec.TITLE_PREFIX + "Diary";
	private static final String TYPED = "open sesame";

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private final List<Runnable> queued = new ArrayList<>();

	private VaultViewModel queuedModel(VaultViewModelRig rig) {
		return new VaultViewModel(rig.app, rig.vault.manager, queued::add,
				rig.store, rig.gate);
	}

	private void runTheBackground() {
		while (!queued.isEmpty()) queued.remove(0).run();
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static char[] overwritten(char[] handedOver) {
		Arrays.fill(handedOver, 'x');
		return handedOver;
	}

	private static byte[] opened(VaultViewModelRig rig, VaultItem note,
			char[] password) throws Exception {
		return ProtectedNoteCodec.open(rig.vault.raw(note.id), password);
	}

	@Test
	public void aQueuedNoteIsSealedUnderThePasswordAsItWasHandedOver()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = queuedModel(rig);
		char[] typed = TYPED.toCharArray();

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"dear diary", typed, null);
		assertEquals(1, queued.size());
		assertArrayEquals("the handed-over array still holds the password",
				new char[TYPED.length()], typed);
		char[] later = overwritten(typed).clone();
		runTheBackground();

		VaultItem note = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(note);
		byte[] text = opened(rig, note, TYPED.toCharArray());
		assertNotNull("the note does not open with the typed password",
				text);
		assertArrayEquals(bytes("dear diary"), text);
		assertNull("the note opens with an all-zero password",
				opened(rig, note, new char[TYPED.length()]));
		assertNull("the note opens with what the array held afterwards",
				opened(rig, note, later));
	}

	@Test
	public void aQueuedReplacementStaysReadableWhenTheCallerWipesItsArray()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = queuedModel(rig);
		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"dear diary", TYPED.toCharArray(), null);
		runTheBackground();
		VaultItem old = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(old);
		char[] typed = TYPED.toCharArray();

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"second entry", typed, old.id);
		Arrays.fill(typed, '\0');
		runTheBackground();

		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		byte[] text = opened(rig, now, TYPED.toCharArray());
		assertNotNull("the only version left does not open", text);
		assertArrayEquals(bytes("second entry"), text);
	}

	@Test
	public void aNoteRefusedWhileLockedWipesThePasswordAndSaysSo()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = queuedModel(rig);
		rig.vault.unlocked = false;
		char[] typed = TYPED.toCharArray();
		long request = vm.newNoteSaveRequest();

		vm.saveNoteWithPassword(request, "Diary", "dear diary", typed, null);

		assertTrue(queued.isEmpty());
		assertArrayEquals(new char[TYPED.length()], typed);
		assertFalse(vm.isNoteSaveRunning(request));
		NoteSaveResult result =
				vm.getNoteSaveResult().getValue().getIfNotHandled();
		assertNotNull(result);
		assertEquals(request, result.request);
		assertEquals(NoteSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(rig.app.getString(R.string.vault_msg_unlock_first),
				result.message);
		verify(rig.vault.manager, never()).addItem(any(), anyString(),
				any());
	}

	@Test
	public void aQueuedPasswordChangeUsesThePasswordsAsTheyWereHandedOver()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = queuedModel(rig);
		List<String> received = new ArrayList<>();
		List<char[]> used = new ArrayList<>();
		doAnswer(a -> {
			char[] current = a.getArgument(0);
			char[] next = a.getArgument(1);
			received.add(new String(current) + " -> " + new String(next));
			used.add(current);
			used.add(next);
			return null;
		}).when(rig.vault.manager).changePassword(any(), any());
		char[] current = "old pass 1!Aa".toCharArray();
		char[] next = "new pass 2@Bb".toCharArray();

		vm.changePassword(current, next);
		assertEquals(1, queued.size());
		overwritten(current);
		overwritten(next);
		runTheBackground();

		assertEquals("the vault was given a changed password",
				Arrays.asList("old pass 1!Aa -> new pass 2@Bb"), received);
		for (char[] copy : used) {
			assertArrayEquals("a copy of a password was left in memory",
					new char[copy.length], copy);
		}
		assertEquals(rig.app.getString(
				R.string.vault_settings_password_changed),
				vm.getSuccessMessage().getValue());
	}

	@Test
	public void aPasswordChangeRefusedWhileLockedWipesBothPasswords()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = queuedModel(rig);
		rig.vault.unlocked = false;
		char[] current = "old pass 1!Aa".toCharArray();
		char[] next = "new pass 2@Bb".toCharArray();

		vm.changePassword(current, next);

		assertTrue(queued.isEmpty());
		assertArrayEquals(new char[current.length], current);
		assertArrayEquals(new char[next.length], next);
		assertEquals(rig.app.getString(R.string.vault_msg_unlock_first),
				vm.getErrorMessage().getValue());
		verify(rig.vault.manager, never()).changePassword(any(), any());
	}
}
