package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@NotNullByDefault
public final class XmrNodeSelector {

	public static final List<XmrNode> VETTED_DEFAULT = Collections.unmodifiableList(
			Arrays.asList(
					XmrNode.parse("2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089", XmrNode.Source.VETTED, false),
					XmrNode.parse("4iv75ceaj2xjqne6d5d35xxk7lkcj6zdtpsbp7sq6sobp44b7txqrcid.onion:18089", XmrNode.Source.VETTED, false),
					XmrNode.parse("44uevwj7nwnyad6chjd4jvg4gopgoegb4flto4g7kxaeteejlxq5ufqd.onion:18089", XmrNode.Source.VETTED, false),
					XmrNode.parse("6ojekl3uqinseoiigf3zgfj7hvxvjld76fguygpn7u74cxbkvegtidid.onion:18089", XmrNode.Source.VETTED, false)));

	private XmrNodeSelector() {
	}

	public static List<XmrNode> failoverOrder(@Nullable XmrNode userOwned,
			List<XmrNode> custom, boolean useVettedDefault,
			@Nullable XmrNode directNode) {
		if (directNode != null) {
			if (directNode.source != XmrNode.Source.DIRECT) {
				throw new IllegalArgumentException(
						"directNode must be a Direct-mode node");
			}
			List<XmrNode> only = new ArrayList<>();
			only.add(directNode);
			return only;
		}
		List<XmrNode> out = new ArrayList<>();
		if (userOwned != null) out.add(userOwned);
		if (useVettedDefault) out.addAll(VETTED_DEFAULT);
		out.addAll(custom);
		List<XmrNode> deduped = new ArrayList<>();
		for (XmrNode n : out) {
			boolean seen = false;
			for (XmrNode m : deduped) {
				if (m.host.equals(n.host) && m.port == n.port) {
					seen = true;
					break;
				}
			}
			if (!seen) deduped.add(n);
		}
		return deduped;
	}

	public static boolean allOverTorExceptDirect(List<XmrNode> order) {
		for (XmrNode n : order) {
			if (!n.usesTor() && n.source != XmrNode.Source.DIRECT) return false;
		}
		return true;
	}
}
