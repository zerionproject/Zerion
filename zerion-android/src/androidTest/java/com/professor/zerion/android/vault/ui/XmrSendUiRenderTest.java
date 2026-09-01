package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders the XMR Send and Review layouts off-screen to PNG files so the visual
 * design can be reviewed without a funded wallet and without defeating the
 * vault's FLAG_SECURE screenshot protection. No wallet, no vault, no network.
 */
@RunWith(AndroidJUnit4.class)
public class XmrSendUiRenderTest {

	private static final int WIDTH = 1080;

	private Context themed() {
		Context base = ApplicationProvider.getApplicationContext();
		return new ContextThemeWrapper(base, R.style.ZerionTheme_NoActionBar);
	}

	private void renderToPng(View v, String name) throws Exception {
		int wSpec = View.MeasureSpec.makeMeasureSpec(WIDTH,
				View.MeasureSpec.EXACTLY);
		int hSpec = View.MeasureSpec.makeMeasureSpec(0,
				View.MeasureSpec.UNSPECIFIED);
		v.measure(wSpec, hSpec);
		int h = Math.max(v.getMeasuredHeight(), 100);
		v.layout(0, 0, WIDTH, h);
		Bitmap bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(bmp);
		c.drawColor(Color.WHITE);
		v.draw(c);
		File dir = ApplicationProvider.getApplicationContext()
				.getExternalFilesDir(null);
		File out = new File(dir, name);
		try (FileOutputStream fos = new FileOutputStream(out)) {
			bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
		}
	}

	@Test
	public void renderSendInput() throws Exception {
		Context ctx = themed();
		View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_xmr_send,
				null, false);
		((TextView) v.findViewById(R.id.xmr_send_available))
				.setText("0.48231954 XMR");
		com.google.android.material.button.MaterialButtonToggleGroup prio =
				v.findViewById(R.id.xmr_priority_toggle);
		prio.check(R.id.xmr_prio_normal);
		renderToPng(v, "xmr_send_input.png");
	}

	@Test
	public void renderSendResult() throws Exception {
		Context ctx = themed();
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.fragment_xmr_send_result, null, false);
		TextView badge = v.findViewById(R.id.result_badge);
		badge.setText("✓");
		badge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
				ctx.getResources().getColor(R.color.zerion_success, null)));
		((TextView) v.findViewById(R.id.result_headline)).setText("Sent");
		TextView amt = v.findViewById(R.id.result_amount);
		amt.setText("0.25000000 XMR");
		amt.setVisibility(View.VISIBLE);
		((TextView) v.findViewById(R.id.result_subtext)).setText(
				"The transaction is broadcasting. It will appear as pending "
						+ "until the network confirms it.");
		android.widget.LinearLayout card = v.findViewById(R.id.result_txid_card);
		card.setVisibility(View.VISIBLE);
		TextView label = new TextView(ctx);
		label.setText("Transaction ID");
		label.setTextColor(ctx.getResources().getColor(
				R.color.zerion_text_secondary, null));
		label.setTextSize(12);
		card.addView(label);
		TextView id = new TextView(ctx);
		id.setText("2b4d9f1c8a7e6035bd2149f0c7ea88b13d5f6072a9c1e4d8f0b3a25c7e9014fd");
		id.setTypeface(android.graphics.Typeface.MONOSPACE);
		id.setTextColor(ctx.getResources().getColor(
				R.color.zerion_text_primary, null));
		id.setTextSize(13);
		card.addView(id);
		com.google.android.material.button.MaterialButton primary =
				v.findViewById(R.id.result_primary);
		primary.setText("Done");
		renderToPng(v, "xmr_send_result.png");
	}

	@Test
	public void renderTxDetail() throws Exception {
		Context ctx = themed();
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.dialog_xmr_tx_detail, null, false);
		((TextView) v.findViewById(R.id.detail_direction)).setText("Received");
		TextView amt = v.findViewById(R.id.detail_amount);
		amt.setText("+0.10600000 XMR");
		amt.setTextColor(ctx.getResources().getColor(R.color.zerion_success,
				null));
		TextView state = v.findViewById(R.id.detail_state);
		state.setText("Confirmed");
		state.setTextColor(ctx.getResources().getColor(R.color.zerion_success,
				null));
		android.widget.LinearLayout rows = v.findViewById(R.id.detail_rows);
		addDetailRow(ctx, rows, "Confirmations", "42");
		addDetailRow(ctx, rows, "Block height", "3 209 774");
		addDetailRow(ctx, rows, "Date", "31 Aug 2026, 14:22");
		((TextView) v.findViewById(R.id.detail_id)).setText(
				"9f1c8a7e6035bd2149f0c7ea88b13d5f6072a9c1e4d8f0b3a25c7e9014fd2b4d");
		renderToPng(v, "xmr_tx_detail.png");
	}

	private static void addDetailRow(Context ctx, android.widget.LinearLayout box,
			String label, String value) {
		android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
		row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		android.widget.LinearLayout.LayoutParams rlp =
				new android.widget.LinearLayout.LayoutParams(
						android.view.ViewGroup.LayoutParams.MATCH_PARENT,
						android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
		rlp.bottomMargin = 24;
		row.setLayoutParams(rlp);
		TextView l = new TextView(ctx);
		l.setText(label);
		l.setTextColor(ctx.getResources().getColor(R.color.zerion_text_secondary,
				null));
		l.setTextSize(14);
		l.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		row.addView(l);
		TextView vv = new TextView(ctx);
		vv.setText(value);
		vv.setTextColor(ctx.getResources().getColor(R.color.zerion_text_primary,
				null));
		vv.setTextSize(14);
		row.addView(vv);
		box.addView(row);
	}

	@Test
	public void renderSendReview() throws Exception {
		Context ctx = themed();
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.dialog_xmr_send_review, null, false);
		((TextView) v.findViewById(R.id.review_amount)).setText("0.25000000 XMR");
		((TextView) v.findViewById(R.id.review_to)).setText(
				"48jewbG1ye8v6b6yQ7kY6C6xXhabQx1w2P8kС9y2mZ4t7pK3fS9nR2dV5aQ8cU7"
						+ "bT6mN1oP4rW3xY2zA9sD8fG5hJ");
		((TextView) v.findViewById(R.id.review_type)).setText("Standard");
		((TextView) v.findViewById(R.id.review_fee)).setText("0.00003210 XMR");
		((TextView) v.findViewById(R.id.review_total)).setText("0.25003210 XMR");
		((TextView) v.findViewById(R.id.review_from)).setText("From  Savings");
		renderToPng(v, "xmr_send_review.png");
	}
}
