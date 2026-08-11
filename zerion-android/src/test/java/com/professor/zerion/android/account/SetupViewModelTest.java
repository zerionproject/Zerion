package com.professor.zerion.android.account;

import android.app.Application;
import android.content.Context;

import org.briarproject.android.dontkillmelib.DozeHelper;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.ImmediateExecutor;
import com.professor.zerion.android.account.SetupViewModel.State;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Rule;
import org.junit.Test;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import static junit.framework.Assert.assertEquals;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static com.professor.zerion.android.account.SetupViewModel.State.CREATED;
import static com.professor.zerion.android.viewmodel.LiveEventTestUtil.getOrAwaitValue;

public class SetupViewModelTest extends BrambleMockTestCase {

	@Rule
	public final InstantTaskExecutorRule testRule =
			new InstantTaskExecutorRule();

	private final String authorName = getRandomString(MAX_AUTHOR_NAME_LENGTH);
	private final char[] password = getRandomString(10).toCharArray();

	private final Application app;
	private final Context appContext;
	private final AccountManager accountManager;
	private final DozeHelper dozeHelper;

	public SetupViewModelTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		app = context.mock(Application.class);
		appContext = context.mock(Context.class);
		accountManager = context.mock(AccountManager.class);
		dozeHelper = context.mock(DozeHelper.class);
	}

	@Test
	public void testCreateAccount() throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).accountExists();
			will(returnValue(false));
			allowing(dozeHelper).needToShowDoNotKillMeFragment(app);
			allowing(app).getApplicationContext();
			will(returnValue(appContext));
			allowing(appContext).getPackageManager();

			oneOf(accountManager).createAccount(authorName, password);
			will(returnValue(true));
		}});

		SetupViewModel viewModel = new SetupViewModel(app,
				accountManager,
				new ImmediateExecutor(),
				context.mock(PasswordStrengthEstimator.class),
				dozeHelper);

		viewModel.setAuthorName(authorName);
		viewModel.setPassword(password);

		State state = getOrAwaitValue(viewModel.getState());
		assertEquals(CREATED, state);
	}
}
