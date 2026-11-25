package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.VaultManager.UnlockResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultUnlockConfirmationTest {

	private static final String TYPED = "vault password 4&Rt";
	private static final String TYPO = "vault password 4&Ry";

	private static VaultViewModel model;

	static {
		TestAndroidKeyStore.register();
	}

	public static class Host extends AppCompatActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(savedInstanceState);
			FrameLayout frame = new FrameLayout(this);
			frame.setId(R.id.fragmentContainer);
			setContentView(frame);
		}

		@Override
		public void runOnDbThread(Runnable runnable) {
		}

		@Override
		public ActivityComponent getActivityComponent() {
			return null;
		}

		@Override
		public void showNextFragment(BaseFragment f) {
		}

		@Override
		public void handleException(Exception e) {
		}
	}

	public static class Unlock extends VaultUnlockFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
			viewModelFactory = new ViewModelProvider.Factory() {
				@NonNull
				@Override
				public <T extends ViewModel> T create(
						@NonNull Class<T> modelClass) {
					return modelClass.cast(model);
				}
			};
		}
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private VaultViewModelRig rig;
	private VaultViewModel vm;
	private final List<String> firstEntries = new ArrayList<>();
	private final List<String> secondEntries = new ArrayList<>();
	private final List<char[]> handedOver = new ArrayList<>();
	private UnlockResult afterConfirming = UnlockResult.OPENED;

	@Before
	public void setUp() throws Exception {
		rig = new VaultViewModelRig();
		rig.vault.unlocked = false;
		when(rig.vault.manager.unlock(any(), anyLong()))
				.thenReturn(UnlockResult.CONFIRM_NEEDED);
		when(rig.vault.manager.confirmAndUnlock(any(), any(), anyLong()))
				.thenAnswer(a -> {
					char[] first = a.getArgument(0);
					char[] second = a.getArgument(1);
					firstEntries.add(new String(first));
					secondEntries.add(new String(second));
					handedOver.add(first);
					handedOver.add(second);
					if (afterConfirming == UnlockResult.OPENED) {
						rig.vault.unlocked = true;
					}
					return afterConfirming;
				});
		vm = rig.newModel();
		model = vm;
	}

	@After
	public void tearDown() {
		model = null;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private char[] kept() throws Exception {
		return (char[]) VaultViewModelRig.field(vm, "firstUnlockEntry");
	}

	private boolean asksAgain() {
		return Boolean.TRUE.equals(vm.getUnlockConfirmation().getValue());
	}

	private String message(int id) {
		return rig.app.getString(id);
	}

	private static void assertWiped(char[] chars) {
		assertArrayEquals(new char[chars.length], chars);
	}

	@Test
	public void aVaultSavedWithoutItsPasswordAsksForThePasswordAgain()
			throws Exception {
		char[] typed = TYPED.toCharArray();

		vm.unlockVault(typed);

		assertTrue(asksAgain());
		assertNull(vm.getErrorMessage().getValue());
		assertNotEquals(VaultViewModel.VaultState.UNLOCKED,
				vm.getVaultState().getValue());
		assertEquals(TYPED, new String(kept()));
		assertWiped(typed);
		verify(rig.vault.manager, never()).confirmAndUnlock(any(), any(),
				anyLong());
	}

	@Test
	public void twoEqualEntriesAreHandedOverTogetherAndThenForgotten()
			throws Exception {
		vm.unlockVault(TYPED.toCharArray());
		char[] first = kept();
		char[] second = TYPED.toCharArray();

		vm.unlockVault(second);

		assertEquals(1, firstEntries.size());
		assertEquals(TYPED, firstEntries.get(0));
		assertEquals(TYPED, secondEntries.get(0));
		assertEquals(VaultViewModel.VaultState.UNLOCKED,
				vm.getVaultState().getValue());
		assertFalse(asksAgain());
		assertNull(kept());
		assertWiped(first);
		assertWiped(second);
		for (char[] given : handedOver) assertWiped(given);
		verify(rig.vault.manager, times(1)).unlock(any(), anyLong());
	}

	@Test
	public void entriesThatDifferShowTheirMessageAndStartAgain()
			throws Exception {
		afterConfirming = UnlockResult.ENTRIES_DIFFER;
		vm.unlockVault(TYPO.toCharArray());
		char[] first = kept();

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(TYPO, firstEntries.get(0));
		assertEquals(TYPED, secondEntries.get(0));
		assertEquals(message(R.string.vault_unlock_entries_differ),
				vm.getErrorMessage().getValue());
		assertFalse(asksAgain());
		assertNull(kept());
		assertWiped(first);
		assertNotEquals(VaultViewModel.VaultState.UNLOCKED,
				vm.getVaultState().getValue());

		vm.unlockVault(TYPED.toCharArray());

		assertTrue(asksAgain());
		assertEquals(TYPED, new String(kept()));
		verify(rig.vault.manager, times(2)).unlock(any(), anyLong());
		verify(rig.vault.manager, times(1)).confirmAndUnlock(any(), any(),
				anyLong());
	}

	@Test
	public void aVaultNotConfirmedOnStorageIsNotCalledAWrongPassword()
			throws Exception {
		afterConfirming = UnlockResult.NOT_CONFIRMED_ON_STORAGE;
		vm.unlockVault(TYPED.toCharArray());

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(message(R.string.vault_unlock_not_confirmed),
				vm.getErrorMessage().getValue());
		assertNotEquals(message(R.string.vault_unlock_failed),
				vm.getErrorMessage().getValue());
		assertFalse(asksAgain());
		assertNull(kept());
		assertNotEquals(VaultViewModel.VaultState.UNLOCKED,
				vm.getVaultState().getValue());
	}

	private void assertDiscardedBy(Runnable event) throws Exception {
		vm.unlockVault(TYPED.toCharArray());
		char[] first = kept();
		assertNotNull(first);

		event.run();

		assertWiped(first);
		assertNull(kept());
		assertFalse(asksAgain());

		vm.unlockVault(TYPED.toCharArray());

		assertTrue(asksAgain());
		verify(rig.vault.manager, never()).confirmAndUnlock(any(), any(),
				anyLong());
	}

	@Test
	public void aLockOfTheVaultBetweenTheEntriesDiscardsTheFirst()
			throws Exception {
		assertDiscardedBy(rig.vault::runLockListeners);
	}

	@Test
	public void aLockRequestBetweenTheEntriesDiscardsTheFirst()
			throws Exception {
		assertDiscardedBy(vm::lockVault);
	}

	@Test
	public void leavingTheAppBetweenTheEntriesDiscardsTheFirst()
			throws Exception {
		assertDiscardedBy(vm::lockIfUnlocked);
	}

	@Test
	public void leavingTheScreenBetweenTheEntriesDiscardsTheFirst()
			throws Exception {
		assertDiscardedBy(vm::discardUnlockConfirmation);
	}

	@Test
	public void aLockDuringTheFirstEntryDropsItsResult() throws Exception {
		when(rig.vault.manager.unlock(any(), anyLong())).thenAnswer(a -> {
			rig.vault.runLockListeners();
			return UnlockResult.CONFIRM_NEEDED;
		});

		vm.unlockVault(TYPED.toCharArray());

		assertFalse(asksAgain());
		assertNull(kept());
	}

	@Test
	public void aRecoveryPasswordAgainstTheRuleNamesTheRuleAndAsksNothing()
			throws Exception {
		when(rig.vault.manager.unlock(any(), anyLong()))
				.thenReturn(UnlockResult.NEW_PASSWORD_REFUSED);

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(message(R.string.vault_password_rule),
				vm.getErrorMessage().getValue());
		assertFalse(asksAgain());
		assertNull(kept());
	}

	@Test
	public void aSecondEntryAgainstTheRuleNamesTheRuleAndStartsAgain()
			throws Exception {
		afterConfirming = UnlockResult.NEW_PASSWORD_REFUSED;
		vm.unlockVault(TYPED.toCharArray());
		char[] first = kept();

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(message(R.string.vault_password_rule),
				vm.getErrorMessage().getValue());
		assertFalse(asksAgain());
		assertNull(kept());
		assertWiped(first);
		assertNotEquals(VaultViewModel.VaultState.UNLOCKED,
				vm.getVaultState().getValue());
	}

	@Test
	public void aRefusedEntryIsStillAnInvalidPassword() throws Exception {
		when(rig.vault.manager.unlock(any(), anyLong()))
				.thenReturn(UnlockResult.REFUSED);

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(message(R.string.vault_unlock_failed),
				vm.getErrorMessage().getValue());
		assertFalse(asksAgain());
		assertNull(kept());
	}

	@Test
	public void aThrottledUnlockSaysToWaitAndNotThatThePasswordIsWrong()
			throws Exception {
		when(rig.vault.manager.unlock(any(), anyLong())).thenThrow(
				new SecurityException(VaultManager.TOO_MANY_ATTEMPTS
						+ ". Wait 3 seconds"));

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(message(R.string.vault_msg_too_many_attempts),
				vm.getErrorMessage().getValue());
		assertNotEquals(message(R.string.vault_unlock_failed),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void anotherSecurityRefusalKeepsTheInvalidPasswordMessage()
			throws Exception {
		when(rig.vault.manager.unlock(any(), anyLong())).thenThrow(
				new SecurityException("Vault key missing"));

		vm.unlockVault(TYPED.toCharArray());

		assertEquals(message(R.string.vault_unlock_failed),
				vm.getErrorMessage().getValue());
	}

	private final List<Runnable> queued = new ArrayList<>();

	private void unlockInTheBackground() {
		vm = new VaultViewModel(rig.app, rig.vault.manager, queued::add,
				rig.store, rig.gate);
		model = vm;
	}

	private void enter(Host host, String typed) {
		SecurePasswordEditText field =
				host.findViewById(R.id.vault_password_input);
		field.setText(typed);
		host.findViewById(R.id.vault_unlock_button).performClick();
		idle();
		assertEquals(0, field.length());
		while (!queued.isEmpty()) queued.remove(0).run();
		idle();
	}

	private Host showUnlockScreen(ActivityController<Host> controller) {
		Host host = controller.get();
		host.getSupportFragmentManager().beginTransaction()
				.add(R.id.fragmentContainer, new Unlock(), "unlock")
				.commitNow();
		idle();
		return host;
	}

	private static String text(Host host, int id) {
		return ((TextView) host.findViewById(id)).getText().toString();
	}

	@Test
	public void theUnlockScreenExplainsTheSecondEntryAndForgetsItWhenLeft()
			throws Exception {
		unlockInTheBackground();
		ActivityController<Host> controller =
				Robolectric.buildActivity(Host.class).setup();
		Host host = showUnlockScreen(controller);
		SecurePasswordEditText field =
				host.findViewById(R.id.vault_password_input);
		TextInputLayout layout =
				host.findViewById(R.id.vault_password_layout);
		assertEquals(message(R.string.vault_unlock_title),
				text(host, R.id.vault_unlock_title_text));
		assertEquals(message(R.string.vault_unlock_subtitle),
				text(host, R.id.vault_unlock_subtitle_text));

		enter(host, TYPED);

		assertEquals(message(R.string.vault_unlock_confirm_title),
				text(host, R.id.vault_unlock_title_text));
		assertEquals(message(R.string.vault_unlock_confirm_message),
				text(host, R.id.vault_unlock_subtitle_text));
		assertEquals(0, field.length());
		assertNull(layout.getError());
		char[] first = kept();
		assertEquals(TYPED, new String(first));

		controller.pause().stop();
		idle();

		assertWiped(first);
		assertNull(kept());

		controller.start().resume();
		idle();

		assertEquals(message(R.string.vault_unlock_title),
				text(host, R.id.vault_unlock_title_text));
		assertEquals(message(R.string.vault_unlock_subtitle),
				text(host, R.id.vault_unlock_subtitle_text));
	}

	@Test
	public void theUnlockScreenShowsTheMismatchAndReturnsToTheFirstEntry()
			throws Exception {
		afterConfirming = UnlockResult.ENTRIES_DIFFER;
		unlockInTheBackground();
		ActivityController<Host> controller =
				Robolectric.buildActivity(Host.class).setup();
		Host host = showUnlockScreen(controller);
		SecurePasswordEditText field =
				host.findViewById(R.id.vault_password_input);
		TextInputLayout layout =
				host.findViewById(R.id.vault_password_layout);

		enter(host, TYPO);
		assertEquals(message(R.string.vault_unlock_confirm_title),
				text(host, R.id.vault_unlock_title_text));
		enter(host, TYPED);

		assertEquals(message(R.string.vault_unlock_entries_differ),
				String.valueOf(layout.getError()));
		assertEquals(message(R.string.vault_unlock_title),
				text(host, R.id.vault_unlock_title_text));
		assertEquals(0, field.length());
		assertNull(kept());
		assertEquals(Arrays.asList(TYPO), firstEntries);
	}
}
