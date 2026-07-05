package com.professor.zerion.android.decoy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.splash.SplashScreenActivity;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.Nullable;

@NotNullByDefault
public class DecoyCalculatorActivity extends Activity {

	public static final String EXTRA_DECOY_PASSED =
			"com.professor.zerion.android.decoy.PASSED";

	private static final java.util.concurrent.atomic.AtomicBoolean
			DECOY_PASSED_TOKEN =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	public static boolean consumeDecoyPassed() {
		return DECOY_PASSED_TOKEN.getAndSet(false);
	}

	private static final char OP_ADD = '+';
	private static final char OP_SUB = '-';
	private static final char OP_MUL = '*';
	private static final char OP_DIV = '/';

	private TextView display;
	private final StringBuilder input = new StringBuilder();
	private final List<Character> rawInput = new ArrayList<>();

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_decoy_calculator);
		display = findViewById(R.id.decoyDisplay);
		bindDigit(R.id.btn0, '0');
		bindDigit(R.id.btn1, '1');
		bindDigit(R.id.btn2, '2');
		bindDigit(R.id.btn3, '3');
		bindDigit(R.id.btn4, '4');
		bindDigit(R.id.btn5, '5');
		bindDigit(R.id.btn6, '6');
		bindDigit(R.id.btn7, '7');
		bindDigit(R.id.btn8, '8');
		bindDigit(R.id.btn9, '9');
		bindDigit(R.id.btnDot, '.');
		bindOp(R.id.btnAdd, OP_ADD);
		bindOp(R.id.btnSub, OP_SUB);
		bindOp(R.id.btnMul, OP_MUL);
		bindOp(R.id.btnDiv, OP_DIV);
		findViewById(R.id.btnClear).setOnClickListener(v -> onClear());
		findViewById(R.id.btnDel).setOnClickListener(v -> onDel());
		findViewById(R.id.btnPercent).setOnClickListener(v -> onPercent());
		findViewById(R.id.btnEq).setOnClickListener(v -> onEquals());
		refresh();
	}

	private void bindDigit(int id, char digit) {
		findViewById(id).setOnClickListener(v -> {
			input.append(digit);
			rawInput.add(digit);
			refresh();
		});
	}

	private void bindOp(int id, char op) {
		findViewById(id).setOnClickListener(v -> {
			if (input.length() == 0) return;
			char last = input.charAt(input.length() - 1);
			if (isOp(last)) {
				input.setLength(input.length() - 1);
				if (!rawInput.isEmpty()) {
					rawInput.remove(rawInput.size() - 1);
				}
			}
			input.append(op);
			rawInput.add(op);
			refresh();
		});
	}

	private void onClear() {
		input.setLength(0);
		Arrays.fill(toChars(rawInput), '\0');
		rawInput.clear();
		refresh();
	}

	private void onDel() {
		if (input.length() == 0) return;
		input.setLength(input.length() - 1);
		if (!rawInput.isEmpty()) {
			rawInput.remove(rawInput.size() - 1);
		}
		refresh();
	}

	private void onPercent() {
		if (input.length() == 0) return;
		try {
			double v = Double.parseDouble(input.toString()) / 100d;
			input.setLength(0);
			input.append(format(v));
		} catch (NumberFormatException ignored) {
		}
		refresh();
	}

	private void onEquals() {
		char[] candidate = toChars(rawInput);
		try {
			if (DecoyConfig.verify(this, candidate)) {
				DECOY_PASSED_TOKEN.set(true);
				Intent i = new Intent(this, SplashScreenActivity.class);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
						| Intent.FLAG_ACTIVITY_CLEAR_TASK);
				startActivity(i);
				finish();
				return;
			}
		} finally {
			Arrays.fill(candidate, '\0');
		}
		Double result = evaluate(input.toString());
		input.setLength(0);
		rawInput.clear();
		if (result != null) {
			input.append(format(result));
		} else {
			input.append("0");
		}
		refresh();
	}

	private void refresh() {
		display.setText(input.length() == 0 ? "0" : input.toString());
	}

	private static boolean isOp(char c) {
		return c == OP_ADD || c == OP_SUB
				|| c == OP_MUL || c == OP_DIV;
	}

	private static char[] toChars(List<Character> src) {
		char[] out = new char[src.size()];
		for (int i = 0; i < out.length; i++) out[i] = src.get(i);
		return out;
	}

	@Nullable
	private static Double evaluate(String expr) {
		if (expr.isEmpty()) return null;
		List<Double> nums = new ArrayList<>();
		List<Character> ops = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < expr.length(); i++) {
			char c = expr.charAt(i);
			if (isOp(c) && cur.length() > 0) {
				try {
					nums.add(Double.parseDouble(cur.toString()));
				} catch (NumberFormatException e) {
					return null;
				}
				cur.setLength(0);
				ops.add(c);
			} else {
				cur.append(c);
			}
		}
		if (cur.length() > 0) {
			try {
				nums.add(Double.parseDouble(cur.toString()));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (nums.isEmpty()) return null;
		for (int i = 0; i < ops.size(); i++) {
			char op = ops.get(i);
			if (op == OP_MUL || op == OP_DIV) {
				double a = nums.get(i);
				double b = nums.get(i + 1);
				double r;
				if (op == OP_DIV) {
					if (b == 0d) return null;
					r = a / b;
				} else {
					r = a * b;
				}
				nums.set(i, r);
				nums.remove(i + 1);
				ops.remove(i);
				i--;
			}
		}
		double acc = nums.get(0);
		for (int i = 0; i < ops.size(); i++) {
			double b = nums.get(i + 1);
			acc = ops.get(i) == OP_ADD ? acc + b : acc - b;
		}
		return acc;
	}

	private static String format(double v) {
		if (v == (long) v) {
			return Long.toString((long) v);
		}
		return String.format(java.util.Locale.US, "%.10g", v)
				.replaceAll("0+$", "")
				.replaceAll("\\.$", "");
	}

	@Override
	public void onBackPressed() {
	}
}
