package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.State;

import static com.professor.zerion.android.conversation.ImageAdapter.isBottomRow;
import static com.professor.zerion.android.conversation.ImageAdapter.isLeft;
import static com.professor.zerion.android.conversation.ImageAdapter.isTopRow;
import static com.professor.zerion.android.conversation.ImageAdapter.singleInRow;
import static com.professor.zerion.android.util.UiUtils.isRtl;

@NotNullByDefault
class ImageItemDecoration extends ItemDecoration {

	private final int border;
	private final boolean isRtl;

	ImageItemDecoration(Context ctx) {
		Resources res = ctx.getResources();

		int b = res.getDimensionPixelSize(R.dimen.message_bubble_border);
		int realBorderSize = b % 2 == 0 ? b : b + 1;

		border = realBorderSize / 2;

		isRtl = isRtl(ctx);
	}

	@Override
	public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
			State state) {
		if (state.getItemCount() == 1) return;
		int pos = parent.getChildAdapterPosition(view);
		int num = state.getItemCount();
		boolean start = isLeft(pos) ^ isRtl;
		outRect.top = isTopRow(pos) ? 0 : border;
		outRect.left = start ? 0 : border;
		outRect.right = start && !singleInRow(pos, num) ? border : 0;
		outRect.bottom = isBottomRow(pos, num) ? 0 : border;
	}

}
