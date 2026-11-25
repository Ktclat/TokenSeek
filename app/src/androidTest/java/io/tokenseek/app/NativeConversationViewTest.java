package io.tokenseek.app;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeConversationViewTest {
    @Test public void narrowComposerLeavesRoomForTranscriptAndSwitchesSendToStop() throws JSONException {
        JSONObject initial = new JSONObject().put("locale","en-US").put("scale",130)
                .put("sessionId","chat").put("model",NativeConversation.FLASH);
        JSONObject receiving = new JSONObject(initial.toString()).put("streaming",true).put("draft","")
                .put("messages",new JSONArray().put(new JSONObject().put("role","assistant").put("id","a")
                        .put("blocks",new JSONArray().put(new JSONObject().put("type","text").put("text","Partial answer")))));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.screenWidthDp = 320;
            Context context = base.createConfigurationContext(config);
            ArrayList<String> actions = new ArrayList<>();
            NativeConversation chat = new NativeConversation(context,(name,value) -> actions.add(name + "/" + value));
            chat.render(initial);
            measure(chat);
            assertFalse(button(chat,"Send").isEnabled());
            TextView flash = (TextView)button(chat,"Flash model");
            assertTrue(flash.getLayout().getLineRight(0) <= flash.getWidth());
            EditText field = find(chat,EditText.class);
            field.setText("Hello");
            button(chat,"Send").performClick();
            assertTrue(actions.contains("draft/Hello"));
            assertTrue(actions.contains("send/Hello"));

            chat.render(receiving);
            measure(chat);
            ListView transcript = (ListView)button(chat,"Conversation messages");
            assertTrue(transcript.getHeight() >= chat.getHeight()/2);
            assertEquals("",chat.draft());
            button(chat,"Stop generating").performClick();
            assertTrue(actions.contains("stop/"));
        });
    }

    private static void measure(View v) {
        float density = v.getResources().getDisplayMetrics().density;
        int width=Math.round(density*320),height=Math.round(density*640);
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        v.layout(0,0,width,height);
    }

    private static View button(View root,String description) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches,description,View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View v:matches) if (description.contentEquals(v.getContentDescription())) return v;
        throw new AssertionError("Missing control " + description);
    }

    private static <T extends View> T find(View root,Class<T> kind) {
        if (kind.isInstance(root)) return kind.cast(root);
        if (root instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) {
            T match = find(group.getChildAt(i),kind);
            if (match!=null) return match;
        }
        return null;
    }
}
