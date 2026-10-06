package com.professor.zerion.android.conversation;

import com.professor.zerion.android.view.ZerionRecyclerViewScrollListener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.BooleanSupplier;

@NotNullByDefault
class ConversationScrollListener extends
		ZerionRecyclerViewScrollListener<ConversationAdapter, ConversationItem> {

	private final ConversationViewModel viewModel;
	private final BooleanSupplier readAllowed;

	protected ConversationScrollListener(ConversationAdapter adapter,
			ConversationViewModel viewModel, BooleanSupplier readAllowed) {
		super(adapter);
		this.viewModel = viewModel;
		this.readAllowed = readAllowed;
	}

	@Override
	protected void onItemVisible(ConversationItem item) {
		if (!item.isRead() && readAllowed.getAsBoolean()) {
			viewModel.markMessageRead(item.getGroupId(), item.getId());
			item.markRead();
		}
	}

}
