package com.professor.zerion.android.conversation;

import com.professor.zerion.android.view.ZerionRecyclerViewScrollListener;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
class ConversationScrollListener extends
		ZerionRecyclerViewScrollListener<ConversationAdapter, ConversationItem> {

	private final ConversationViewModel viewModel;

	protected ConversationScrollListener(ConversationAdapter adapter,
			ConversationViewModel viewModel) {
		super(adapter);
		this.viewModel = viewModel;
	}

	@Override
	protected void onItemVisible(ConversationItem item) {
		if (!item.isRead()) {
			viewModel.markMessageRead(item.getGroupId(), item.getId());
			item.markRead();
		}
	}

}
