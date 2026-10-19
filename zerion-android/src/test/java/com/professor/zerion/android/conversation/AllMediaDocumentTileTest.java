package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AllMediaDocumentTileTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static Object item(String type) throws Exception {
		Constructor<?> c = Class.forName(AllMediaActivity.class.getName()
				+ "$MediaItem").getDeclaredConstructors()[0];
		c.setAccessible(true);
		AttachmentHeader h = new AttachmentHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), type);
		return c.newInstance(h, false, false, true, 1000L,
				new MessageId(getRandomId()));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static RecyclerView.ViewHolder tile(String type) throws Exception {
		Constructor<?> c = Class.forName(AllMediaActivity.class.getName()
				+ "$MediaAdapter").getDeclaredConstructors()[0];
		c.setAccessible(true);
		RecyclerView.Adapter adapter = (RecyclerView.Adapter)
				c.newInstance(new AllMediaActivity());
		List<Object> items = new ArrayList<>();
		items.add(item(type));
		Method set = adapter.getClass().getDeclaredMethod("setItems",
				List.class);
		set.setAccessible(true);
		set.invoke(adapter, items);
		RecyclerView.ViewHolder h = adapter.onCreateViewHolder(
				new FrameLayout(ctx()), 0);
		adapter.onBindViewHolder(h, 0);
		return h;
	}

	@Test
	public void documentTilesSayTheKindInPlainWords() throws Exception {
		String[][] cases = {{"application/pdf", "PDF document"},
				{"text/plain", "Text document"}};
		for (String[] c : cases) {
			RecyclerView.ViewHolder h = tile(c[0]);
			TextView name = h.itemView.findViewById(R.id.document_name);
			assertEquals(c[0], c[1], name.getText().toString());
			assertEquals(c[0], c[1],
					String.valueOf(h.itemView.getContentDescription()));
		}
	}
}
