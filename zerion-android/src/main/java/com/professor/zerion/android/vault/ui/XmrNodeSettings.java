package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.vault.wallet.xmr.XmrNode;
import com.professor.zerion.android.vault.wallet.xmr.XmrNodeConfig;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared Monero node-settings flow, so the wallet list and the wallet detail
 * present identical node configuration. {@link #choose} shows the four-tier
 * selector (own / vetted / custom / direct) with a plain-language description of
 * each tier's privacy trade-off; {@link #apply} then prompts for the mode's node
 * address, validates it by parsing the actual node (a malformed address is
 * rejected, never silently downgraded), and for Direct mode requires an explicit
 * clearnet privacy acknowledgement first.
 */
@NotNullByDefault
final class XmrNodeSettings {

	private XmrNodeSettings() {
	}

	/**
	 * Present the four node tiers with title + description, preselecting the
	 * current mode, and apply the chosen tier. The exact host / onion / port is
	 * only ever shown in the per-mode address prompt, not in this overview.
	 */
	static void choose(Fragment f, XmrViewModel vm) {
		Context ctx = f.requireContext();
		XmrNodeConfig cfg = vm.getNodeConfig();
		XmrNodeConfig.Mode[] modes = XmrNodeConfig.Mode.values();
		int[] titles = {
				R.string.wallet_xmr_node_own,
				R.string.wallet_xmr_node_vetted,
				R.string.wallet_xmr_node_custom,
				R.string.wallet_xmr_node_direct
		};
		int[] subs = {
				R.string.wallet_xmr_node_own_sub,
				R.string.wallet_xmr_node_vetted_sub,
				R.string.wallet_xmr_node_custom_sub,
				R.string.wallet_xmr_node_direct_sub
		};
		final int[] sel = {cfg.mode.ordinal()};
		float d = f.getResources().getDisplayMetrics().density;
		int pad = Math.round(16 * d);
		int rowPad = Math.round(10 * d);
		int primary = MaterialColors.getColor(ctx,
				com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
		int secondary = MaterialColors.getColor(ctx,
				com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666);

		LinearLayout list = new LinearLayout(ctx);
		list.setOrientation(LinearLayout.VERTICAL);
		list.setPadding(pad, pad / 2, pad, 0);
		final RadioButton[] radios = new RadioButton[modes.length];
		for (int i = 0; i < modes.length; i++) {
			final int idx = i;
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setPadding(0, rowPad, 0, rowPad);

			RadioButton rb = new RadioButton(ctx);
			rb.setChecked(i == sel[0]);
			radios[i] = rb;
			row.addView(rb);

			LinearLayout texts = new LinearLayout(ctx);
			texts.setOrientation(LinearLayout.VERTICAL);
			texts.setLayoutParams(new LinearLayout.LayoutParams(0,
					ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			TextView title = new TextView(ctx);
			title.setText(titles[i]);
			title.setTextSize(16);
			title.setTextColor(primary);
			TextView sub = new TextView(ctx);
			sub.setText(subs[i]);
			sub.setTextSize(13);
			sub.setTextColor(secondary);
			texts.addView(title);
			texts.addView(sub);
			row.addView(texts);

			View.OnClickListener pick = x -> {
				sel[0] = idx;
				for (int j = 0; j < radios.length; j++) {
					radios[j].setChecked(j == idx);
				}
			};
			row.setOnClickListener(pick);
			rb.setOnClickListener(pick);
			list.addView(row);
		}
		ScrollView scroll = new ScrollView(ctx);
		scroll.addView(list);

		String active = cfg.activeNodeLabel();
		new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_xmr_node_title)
				.setMessage(active == null ? null
						: f.getString(R.string.wallet_xmr_node_active, active))
				.setView(scroll)
				.setPositiveButton(android.R.string.ok,
						(dlg, w) -> apply(f, vm, modes[sel[0]], cfg))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	static void apply(Fragment f, XmrViewModel vm, XmrNodeConfig.Mode mode,
			XmrNodeConfig cfg) {
		switch (mode) {
			case VETTED:
				save(f, vm, new XmrNodeConfig(mode, "", cfg.customNodes, ""));
				break;
			case OWN:
				promptAddress(f, R.string.wallet_xmr_node_own_hint, cfg.ownNode,
						addr -> save(f, vm, new XmrNodeConfig(mode, addr,
								cfg.customNodes, "")));
				break;
			case CUSTOM:
				promptAddress(f, R.string.wallet_xmr_node_custom_hint,
						cfg.customNodes.isEmpty() ? "" : cfg.customNodes.get(0),
						addr -> {
							List<String> c = new ArrayList<>();
							c.add(addr);
							save(f, vm, new XmrNodeConfig(mode, cfg.ownNode, c,
									cfg.directNode));
						});
				break;
			case DIRECT:
				new MaterialAlertDialogBuilder(f.requireContext())
						.setTitle(R.string.wallet_xmr_node_direct)
						.setMessage(R.string.wallet_xmr_node_direct_ack)
						.setPositiveButton(R.string.wallet_xmr_node_direct_accept,
								(d, w) -> promptAddress(f,
										R.string.wallet_xmr_node_direct_hint,
										cfg.directNode, addr -> save(f, vm,
												new XmrNodeConfig(mode, "",
														cfg.customNodes, addr))))
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				break;
		}
	}

	private interface AddrSink {
		void accept(String address);
	}

	private static void promptAddress(Fragment f, int hintRes, String current,
			AddrSink sink) {
		EditText input = new EditText(f.requireContext());
		input.setHint(hintRes);
		input.setText(current);
		input.setSingleLine(true);
		int p = Math.round(20 * f.getResources().getDisplayMetrics().density);
		FrameLayout box = new FrameLayout(f.requireContext());
		box.setPadding(p, p / 2, p, 0);
		box.addView(input);
		new MaterialAlertDialogBuilder(f.requireContext())
				.setTitle(R.string.wallet_xmr_node_address)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String v = input.getText().toString().trim();
					if (!v.isEmpty()) sink.accept(v);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static void save(Fragment f, XmrViewModel vm, XmrNodeConfig cfg) {
		if (!valid(cfg)) {
			toast(f, R.string.wallet_xmr_node_invalid);
			return;
		}
		vm.saveNodeConfig(cfg);
		toast(f, R.string.wallet_xmr_node_saved);
	}

	/** Validate the mode's actual node by parsing it; never via the fallback. */
	private static boolean valid(XmrNodeConfig cfg) {
		try {
			switch (cfg.mode) {
				case OWN:
					XmrNode.parse(cfg.ownNode, XmrNode.Source.USER_OWNED, true);
					return true;
				case CUSTOM:
					XmrNode.parse(cfg.customNodes.isEmpty() ? ""
							: cfg.customNodes.get(0), XmrNode.Source.CUSTOM, false);
					return true;
				case DIRECT:
					XmrNode.parse(cfg.directNode, XmrNode.Source.DIRECT, false);
					return true;
				default:
					return true;
			}
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static void toast(Fragment f, int res) {
		if (f.isAdded()) {
			android.widget.Toast.makeText(f.requireContext(), res,
					android.widget.Toast.LENGTH_SHORT).show();
		}
	}
}
