package com.professor.zerion.android.login;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.ImmediateExecutor;
import org.jmock.Expectations;
import org.junit.Rule;
import org.junit.Test;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_FAILED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_UNCERTAIN;
import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ChangePasswordViewModelTest extends BrambleMockTestCase {

	@Rule
	public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final PasswordStrengthEstimator estimator =
			context.mock(PasswordStrengthEstimator.class);

	private ChangePasswordViewModel viewModel() {
		context.checking(new Expectations() {{
			allowing(accountManager).isEraseRequested();
			will(returnValue(false));
			allowing(accountManager).signInLockoutRemainingMs();
			will(returnValue(0L));
		}});
		ChangePasswordViewModel viewModel = new ChangePasswordViewModel(
				null, accountManager, new ImmediateExecutor(), estimator);
		viewModel.setDuressCheck(typed -> false);
		return viewModel;
	}

	private DecryptionResult changeReportedAs() {
		return viewModel().changePassword("old".toCharArray(),
				"new password".toCharArray()).getLastValue();
	}

	@Test
	public void onlyANormalReturnIsReportedAsSuccess() throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).changePassword(with(any(char[].class)),
					with(any(char[].class)));
		}});
		assertEquals(SUCCESS, changeReportedAs());
	}

	@Test
	public void aKeyReplacementFailureIsReportedAsNotChanged()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).changePassword(with(any(char[].class)),
					with(any(char[].class)));
			will(throwException(
					new DecryptionException(KEY_REPLACEMENT_FAILED)));
		}});
		assertEquals(KEY_REPLACEMENT_FAILED, changeReportedAs());
	}

	@Test
	public void anUnexpectedExceptionEndsTheWaitWithoutSuccess()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).changePassword(with(any(char[].class)),
					with(any(char[].class)));
			will(throwException(new IllegalStateException("injected")));
		}});
		DecryptionResult reported = changeReportedAs();
		assertNotNull("the screen must not wait forever", reported);
		assertEquals(KEY_REPLACEMENT_UNCERTAIN, reported);
	}

	@Test
	public void aRefusedNewPasswordIsReportedAsNotChanged() throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).changePassword(with(any(char[].class)),
					with(any(char[].class)));
			will(throwException(new IllegalArgumentException("empty")));
		}});
		assertEquals(KEY_REPLACEMENT_FAILED, changeReportedAs());
	}

	@Test
	public void anErrorEndsTheWaitAsUnknown() throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).changePassword(with(any(char[].class)),
					with(any(char[].class)));
			will(throwException(new OutOfMemoryError("injected")));
		}});
		assertEquals(KEY_REPLACEMENT_UNCERTAIN, changeReportedAs());
	}

	@Test
	public void theOutcomeIsKeptByTheViewModel() throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).changePassword(with(any(char[].class)),
					with(any(char[].class)));
		}});
		ChangePasswordViewModel viewModel = viewModel();
		viewModel.changePassword("old".toCharArray(),
				"new password".toCharArray());
		assertEquals(SUCCESS, viewModel.getLatestResult().getLastValue());
	}
}
