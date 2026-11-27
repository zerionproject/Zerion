package com.professor.zerion.android.conversation;

import com.professor.zerion.android.view.ZerionRecyclerViewScrollListener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.BooleanSupplier;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

	void markVisibleRead(RecyclerView recyclerView) {
		RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
		if (!(lm instanceof LinearLayoutManager)) return;
		LinearLayoutManager layout = (LinearLayoutManager) lm;
		onItemsVisible(layout.findFirstVisibleItemPosition(),
				layout.findLastVisibleItemPosition(), adapter.getItemCount());
	}

	@Override
	protected void onItemVisible(ConversationItem item) {
		if (!item.isRead() && readAllowed.getAsBoolean()) {
			viewModel.markMessageRead(item.getGroupId(), item.getId());
			item.markRead();
		}
	}

}
