package com.professor.zerion.android.contact.request;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.professor.zerion.R;

import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.IncomingRequestInfo;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequestInfo;
import org.zerionproject.core.api.identity.AuthorId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

final class RequestFixtures {

	public static class Host extends AppCompatActivity {

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(savedInstanceState);
		}
	}

	static final class Queue implements Executor {

		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public void execute(Runnable r) {
			tasks.add(r);
		}

		void runAll() {
			idle();
			for (int round = 0; round < 10 && !tasks.isEmpty(); round++) {
				List<Runnable> now = new ArrayList<>(tasks);
				tasks.clear();
				for (Runnable r : now) r.run();
				idle();
			}
			idle();
		}
	}

	static final class Background implements Executor {

		@Override
		public void execute(Runnable r) {
			Thread t = new Thread(r);
			t.start();
			try {
				t.join();
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		}
	}

	static final class WatchedBanner extends TextView {

		final List<String> wrongThread = new ArrayList<>();

		WatchedBanner(Context context) {
			super(context);
		}

		private void check(String call) {
			if (Looper.myLooper() != Looper.getMainLooper()) {
				wrongThread.add(call);
			}
		}

		@Override
		public void setText(CharSequence text, BufferType type) {
			check("setText");
			super.setText(text, type);
		}

		@Override
		public void setOnClickListener(@Nullable OnClickListener l) {
			check("setOnClickListener");
			super.setOnClickListener(l);
		}

		@Override
		public void setVisibility(int visibility) {
			check("setVisibility");
			super.setVisibility(visibility);
		}
	}

	static WatchedBanner watchBanner(View root) {
		TextView old = root.findViewById(R.id.invitesPausedBanner);
		ViewGroup parent = (ViewGroup) old.getParent();
		int index = parent.indexOfChild(old);
		WatchedBanner banner = new WatchedBanner(old.getContext());
		banner.setId(old.getId());
		parent.removeViewAt(index);
		parent.addView(banner, index, old.getLayoutParams());
		return banner;
	}

	private RequestFixtures() {
	}

	static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	static byte[] id(int b) {
		byte[] id = new byte[16];
		id[0] = (byte) b;
		return id;
	}

	static InviteLink link(InviteLink.Flow flow) {
		int type = flow == InviteLink.Flow.MUTUAL_CLASSICAL ? 0 : 1;
		return new InviteLink(type, new byte[65],
				flow == InviteLink.Flow.REQUEST ? "a" : null, flow);
	}

	static IncomingRequestInfo incoming(int n, @Nullable String name,
			@Nullable String note, int state, int linkKind,
			@Nullable String label, long senderTime, long receivedAt,
			@Nullable PendingContactId pending) {
		IncomingRequest r = new IncomingRequest(id(n), new byte[32],
				new byte[65], name, note, new byte[32], senderTime, receivedAt,
				state, receivedAt + 1, pending);
		return new IncomingRequestInfo(r, linkKind, label);
	}

	static OutgoingRequestInfo outgoing(int n, String alias, int state,
			long createdAt, long deliveredAt,
			@Nullable PendingContactId pending) {
		return new OutgoingRequestInfo(new OutgoingRequest(id(n),
				new AuthorId(new byte[32]), "zerion://x", alias, "Me", null,
				null, null, createdAt, state, 0, 0, deliveredAt, pending, 0));
	}

	static AlertDialog latestAlert() {
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("no dialog", d instanceof AlertDialog);
		return (AlertDialog) d;
	}

	static String allText(View v) {
		StringBuilder sb = new StringBuilder();
		collect(v, sb);
		return sb.toString();
	}

	private static void collect(View v, StringBuilder sb) {
		if (v.getVisibility() != View.VISIBLE) return;
		if (v instanceof TextView) {
			sb.append(((TextView) v).getText()).append('\n');
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), sb);
			}
		}
	}
}
