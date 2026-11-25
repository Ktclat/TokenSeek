package io.tokenseek.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeHistoryTransferTest {

    private static NativeHistoryStore store() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new NativeHistoryStore(context, UUID.randomUUID().toString());
    }

    private static InputStream previousArchive() throws IOException {
        return InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("history-v092.fixture");
    }

    @Test
    public void previousReleaseMergeKeepsLocalRowsAndAttachmentBytes() throws Exception {
        NativeHistoryStore first = store(), second = store();
        try (InputStream original = previousArchive()) {
            assertEquals(2, first.importZip(original, false));
        }
        assertEquals(0, second.sessions().length());
        assertEquals("shared", first.organization().getJSONArray("pinned").getString(0));
        assertEquals("P-shared", first.organization().getJSONObject("assignments").getString("shared"));
        JSONObject session = first.session("shared");
        JSONArray branch = NativeHistoryStore.visibleMessages(session);
        assertEquals("A-shared-QUESTION", branch.getJSONObject(0).getString("content"));
        assertEquals("A-shared-ANSWER", branch.getJSONObject(1).getJSONArray("blocks").getJSONObject(0).getString("text"));
        ByteArrayOutputStream originalFile = new ByteArrayOutputStream();
        assertTrue(first.copyAttachment("F-shared", originalFile));
        assertEquals("A-shared-FILE-BYTES", originalFile.toString("UTF-8"));
        session.put("title", "Local");
        first.saveSession(session);
        try (InputStream original = previousArchive()) {
            assertEquals(0, first.importZip(original, false));
        }
        assertEquals("Local", first.session("shared").getString("title"));
        first.setDraft(null, "Unsent");
        first.setDraft("shared", "Reply draft");
        first.setActiveId("shared");
        byte[] original = new byte[3 * 1024 * 1024];
        Arrays.fill(original, (byte) 71);
        first.saveAttachment("F-shared", new ByteArrayInputStream(original));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        first.exportZip(output, null);
        byte[] bytes = output.toByteArray();
        assertEquals(2, second.importZip(new ByteArrayInputStream(bytes), true));
        assertEquals("Unsent", second.draft(null));
        assertEquals("Reply draft", second.draft("shared"));
        assertEquals("shared", second.activeId());
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        assertTrue(second.copyAttachment("F-shared", file));
        assertArrayEquals(original, file.toByteArray());
        try {
            second.importZip(new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 120)), true);
            fail();
        } catch (IOException expected) {
            assertEquals("Local", second.session("shared").getString("title"));
        }
        String longDraft = new String(new char[2 * 1024 * 1024]).replace('\0', 'x');
        second.setDraft("shared", longDraft);
        assertEquals(longDraft, second.draft("shared"));
        first.deleteSession("shared");
        assertNull(first.activeId());
        assertEquals("", first.draft("shared"));
        assertEquals(0, first.organization().getJSONArray("pinned").length());
        assertEquals("Unsent", first.draft(null));
        assertNotNull(second.session("shared"));
    }

    @Test
    public void nativeArchivePreservesAlternativeResponsesSignaturesAndUsage() throws Exception {
        NativeHistoryStore first = store(), second = store();
        JSONObject s = new JSONObject("{id:'s1',title:'Test',createdAt:1,updatedAt:2,rootMessageId:'u',messages:[" + "{id:'u',role:'user',createdAt:1,parentId:null,selectedChildId:'b',content:'q',files:[]}," + "{id:'a',role:'assistant',createdAt:1,parentId:'u',status:'done',blocks:[{type:'thinking',thinking:'t',signature:'local'}]}," + "{id:'b',role:'assistant',createdAt:2,parentId:'u',status:'done',blocks:[{type:'text',text:'b'}],usageRuns:[{kind:'final',usage:{input_tokens:3,output_tokens:2,cache_read_input_tokens:0,cache_creation_input_tokens:0}}]}]}");
        first.saveSession(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        first.exportZip(out, "s1");
        second.importZip(new ByteArrayInputStream(out.toByteArray()), false);
        assertEquals(s.toString(), second.session("s1").toString());
        assertEquals("b", NativeHistoryStore.visibleMessages(second.session("s1")).getJSONObject(1).getString("id"));
    }
}
