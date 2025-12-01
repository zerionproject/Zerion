package com.professor.zerion.android.privategroup.conversation;

import com.professor.zerion.R;
import org.briarproject.briar.api.privategroup.JoinMessageHeader;

import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;

@UiThread
@NotThreadSafe
class JoinMessageItem extends GroupMessageItem {

	private final boolean isInitial;

	JoinMessageItem(JoinMessageHeader h, String text) {
		super(h, text);
		isInitial = h.isInitial();
	}

	@Override
	public int getLevel() {
		return 0;
	}

	@Override
	@LayoutRes
	public int getLayout() {
		return R.layout.list_item_group_join_notice;
	}

	boolean isInitial() {
		return isInitial;
	}

}
