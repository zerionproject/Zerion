package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.util.SecureClipboard;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/**
 * Recovery phrase screen. Reached deterministically after a committed create
 * (and, with fresh authentication, from wallet settings). The phrase is handed
 * over in memory only, never through arguments or saved state: on process
 * death the screen has nothing to show and sends the user back to re-request
 * it with authentication. The hosting activity sets FLAG_SECURE, so the screen
 * cannot be captured.
 *
 * <p>Backup verification asks for three randomly chosen positions, each with
 * six candidate words drawn from the phrase itself. Only a non-secret
 * "verified" flag is ever persisted; the asked positions, candidates and
 * answers live in this fragment's memory and are cleared when it leaves.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class XmrRecoveryPhraseFragment extends BaseFragment {

	public static final String TAG = "XmrRecoveryPhraseFragment";
	private static final String ARG_ID = "walletId";
	private static final String ARG_NAME = "walletName";
	private static final String ARG_FROM_CREATE = "fromCreate";
	private static final long CLIPBOARD_TTL_MS = 30_000L;
	private static final int CHECK_WORDS = 3;
	private static final int CANDIDATES = 6;
	private static final long TICK_MS = 600L;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private XmrViewModel viewModel;
	private String walletId = "";
	private String walletName = "";
	private boolean fromCreate;
	private boolean verified;
	@Nullable
	private char[] seed;
	@Nullable
	private String[] words;
	private final List<TextView> wordViews = new ArrayList<>();

	private View phraseSection;
	private View verifySection;
	private TextView verifyProgress;
	private TextView verifyPrompt;
	private TextView verifyFeedback;
	private Button laterButton;
	private final Button[] chips = new Button[CANDIDATES];

	@Nullable
	private List<Integer> asked;
	private int step;
	@Nullable
	private String expected;
	private boolean advancing;

	public static XmrRecoveryPhraseFragment newInstance(String walletId,
			String walletName, boolean fromCreate) {
		XmrRecoveryPhraseFragment f = new XmrRecoveryPhraseFragment();
		Bundle b = new Bundle();
		b.putString(ARG_ID, walletId);
		b.putString(ARG_NAME, walletName);
		b.putBoolean(ARG_FROM_CREATE, fromCreate);
		f.setArguments(b);
		return f;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle state) {
		Bundle args = getArguments();
		if (args != null) {
			walletId = args.getString(ARG_ID, "");
			walletName = args.getString(ARG_NAME, "");
			fromCreate = args.getBoolean(ARG_FROM_CREATE, false);
		}
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(XmrViewModel.class);

		seed = viewModel.takeRecoverySeed(walletId);
		if (seed == null) {
			toast(getString(R.string.wallet_xmr_phrase_unavailable));
			leave();
			return new View(requireContext());
		}
		words = tokenizeSeed(seed);

		View v = inflater.inflate(R.layout.fragment_xmr_recovery, container,
				false);
		phraseSection = v.findViewById(R.id.recovery_phrase_section);
		verifySection = v.findViewById(R.id.recovery_verify_section);
		verifyProgress = v.findViewById(R.id.recovery_verify_progress);
		verifyPrompt = v.findViewById(R.id.recovery_verify_prompt);
		verifyFeedback = v.findViewById(R.id.recovery_verify_feedback);
		laterButton = v.findViewById(R.id.recovery_later);
		((TextView) v.findViewById(R.id.recovery_wallet_name))
				.setText(walletName);

		LinearLayout grid = v.findViewById(R.id.recovery_word_grid);
		for (int i = 0; i < words.length; i += 2) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			rp.bottomMargin = dp(8);
			row.setLayoutParams(rp);
			row.addView(wordCell(inflater, row, i, true));
			if (i + 1 < words.length) {
				row.addView(wordCell(inflater, row, i + 1, false));
			} else {
				View spacer = new View(requireContext());
				LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
						1, 1f);
				sp.setMarginStart(dp(8));
				spacer.setLayoutParams(sp);
				row.addView(spacer);
			}
			grid.addView(row);
		}

		v.findViewById(R.id.recovery_back).setOnClickListener(x -> later());
		v.findViewById(R.id.recovery_verify).setOnClickListener(
				x -> startVerification());
		v.findViewById(R.id.recovery_copy).setOnClickListener(x -> confirmCopy());
		laterButton.setOnClickListener(x -> later());
		v.findViewById(R.id.recovery_verify_cancel).setOnClickListener(
				x -> cancelVerification());
		int[] ids = {R.id.recovery_chip_1, R.id.recovery_chip_2,
				R.id.recovery_chip_3, R.id.recovery_chip_4, R.id.recovery_chip_5,
				R.id.recovery_chip_6};
		for (int i = 0; i < CANDIDATES; i++) {
			chips[i] = v.findViewById(ids[i]);
			chips[i].setSaveEnabled(false);
			chips[i].setOnClickListener(this::onChip);
		}

		viewModel.getBackupVerified().observe(getViewLifecycleOwner(), b -> {
			verified = b != null && b;
			laterButton.setText(verified ? R.string.wallet_xmr_phrase_done
					: R.string.wallet_xmr_phrase_later);
		});
		viewModel.loadBackupState(walletId);
		return v;
	}

	private View wordCell(LayoutInflater inflater, ViewGroup parent, int index,
			boolean first) {
		View cell = inflater.inflate(R.layout.item_xmr_recovery_word, parent,
				false);
		LinearLayout.LayoutParams lp =
				(LinearLayout.LayoutParams) cell.getLayoutParams();
		if (first) lp.setMarginEnd(dp(4));
		else lp.setMarginStart(dp(4));
		((TextView) cell.findViewById(R.id.recovery_word_index))
				.setText(String.valueOf(index + 1));
		TextView word = cell.findViewById(R.id.recovery_word_text);
		word.setText(wordAt(index));
		wordViews.add(word);
		return cell;
	}

	private String wordAt(int i) {
		String[] w = words;
		return (w != null && i >= 0 && i < w.length) ? w[i] : "";
	}

	private void confirmCopy() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_phrase_copy_action)
				.setMessage(R.string.wallet_xmr_phrase_copy_warning)
				.setPositiveButton(R.string.wallet_xmr_phrase_copy_confirm,
						(d, w) -> {
							char[] s = seed;
							if (s == null) return;
							SecureClipboard.copySensitive(requireContext(), "",
									new String(s), CLIPBOARD_TTL_MS);
							toast(getString(R.string.wallet_xmr_phrase_copied));
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void startVerification() {
		String[] w = words;
		if (w == null || w.length < CANDIDATES + 1) return;
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < w.length; i++) all.add(i);
		Collections.shuffle(all, new SecureRandom());
		asked = new ArrayList<>(all.subList(0, CHECK_WORDS));
		step = 0;
		phraseSection.setVisibility(View.GONE);
		verifySection.setVisibility(View.VISIBLE);
		showStep();
	}

	private void showStep() {
		List<Integer> a = asked;
		String[] w = words;
		if (a == null || w == null || !isAdded()) return;
		if (step >= a.size()) {
			finishVerification();
			return;
		}
		int index = a.get(step);
		expected = w[index];
		verifyProgress.setText(getString(R.string.wallet_xmr_phrase_verify_step,
				step + 1, a.size()));
		verifyPrompt.setText(getString(R.string.wallet_xmr_phrase_verify_prompt,
				index + 1));
		verifyFeedback.setText("");
		List<String> choices = new ArrayList<>();
		choices.add(expected);
		List<Integer> pool = new ArrayList<>();
		for (int i = 0; i < w.length; i++) {
			if (i != index && !w[i].equals(expected)) pool.add(i);
		}
		Collections.shuffle(pool, new SecureRandom());
		for (int i = 0; i < pool.size() && choices.size() < CANDIDATES; i++) {
			String c = w[pool.get(i)];
			if (!choices.contains(c)) choices.add(c);
		}
		Collections.shuffle(choices, new SecureRandom());
		for (int i = 0; i < CANDIDATES; i++) {
			boolean has = i < choices.size();
			chips[i].setText(has ? choices.get(i) : "");
			chips[i].setEnabled(has);
			chips[i].setVisibility(has ? View.VISIBLE : View.INVISIBLE);
		}
		advancing = false;
	}

	private void onChip(View v) {
		if (advancing || expected == null || !(v instanceof Button)) return;
		CharSequence text = ((Button) v).getText();
		if (text != null && expected.equals(text.toString())) {
			advancing = true;
			verifyFeedback.setText("");
			verifyPrompt.setText("✓");
			for (Button c : chips) c.setEnabled(false);
			step++;
			v.postDelayed(this::showStep, TICK_MS);
		} else {
			restartVerification();
			verifyFeedback.setText(R.string.wallet_xmr_phrase_verify_fail);
		}
	}

	/**
	 * A wrong choice restarts from the first position with freshly chosen
	 * positions and candidates, so progress cannot be accumulated by guessing
	 * one position at a time.
	 */
	private void restartVerification() {
		String[] w = words;
		if (w == null) return;
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < w.length; i++) all.add(i);
		Collections.shuffle(all, new SecureRandom());
		asked = new ArrayList<>(all.subList(0, CHECK_WORDS));
		step = 0;
		showStep();
	}

	private void finishVerification() {
		clearVerification();
		viewModel.setBackupVerified(walletId, true);
		toast(getString(R.string.wallet_xmr_phrase_verified));
		leave();
	}

	private void cancelVerification() {
		clearVerification();
		verifySection.setVisibility(View.GONE);
		phraseSection.setVisibility(View.VISIBLE);
	}

	private void clearVerification() {
		asked = null;
		expected = null;
		step = 0;
		advancing = false;
		for (Button c : chips) {
			if (c != null) c.setText("");
		}
		if (verifyPrompt != null) verifyPrompt.setText("");
		if (verifyFeedback != null) verifyFeedback.setText("");
	}

	private void later() {
		if (!verified && fromCreate) {
			viewModel.setBackupVerified(walletId, false);
			toast(getString(R.string.wallet_xmr_phrase_skip_note));
		}
		leave();
	}

	private void leave() {
		clearSecrets();
		androidx.fragment.app.FragmentManager fm =
				requireActivity().getSupportFragmentManager();
		if (fm.getBackStackEntryCount() > 0) {
			fm.popBackStack();
		} else if (fromCreate) {
			showNextFragment(XmrWalletFragment.newInstance());
		} else {
			showNextFragment(XmrWalletDetailFragment.newInstance(walletId,
					walletName));
		}
	}

	/**
	 * Split the seed char[] into word Strings without ever materialising the
	 * whole phrase as one immutable String. Each word must still become a String
	 * to render in a TextView, but the full-phrase copy that a plain
	 * {@code new String(seed).split(...)} would leave on the heap until GC is
	 * avoided; the per-word Strings are dropped in {@link #clearSecrets()}.
	 */
	private static String[] tokenizeSeed(char[] seed) {
		List<String> out = new ArrayList<>();
		int i = 0;
		int n = seed.length;
		while (i < n) {
			while (i < n && Character.isWhitespace(seed[i])) i++;
			int start = i;
			while (i < n && !Character.isWhitespace(seed[i])) i++;
			if (i > start) out.add(new String(seed, start, i - start));
		}
		return out.toArray(new String[0]);
	}

	private void clearSecrets() {
		clearVerification();
		for (TextView tv : wordViews) tv.setText("");
		wordViews.clear();
		String[] w = words;
		if (w != null) Arrays.fill(w, "");
		words = null;
		char[] s = seed;
		if (s != null) Arrays.fill(s, '\0');
		seed = null;
	}

	@Override
	public void onDestroyView() {
		clearSecrets();
		super.onDestroyView();
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(new Bundle());
	}

	private int dp(int val) {
		return Math.round(val * getResources().getDisplayMetrics().density);
	}

	private void toast(String s) {
		if (isAdded()) {
			Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
		}
	}
}
