package io.tokenseek.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeHistoryLifecycleTest {
    private static NativeThread thread(NativeHistoryStore store) {
        return new NativeThread(store, new NativeAccountStore(store.context), null, new NativeThread.Listener() {
            @Override public void changed() {}
            @Override public void problem(String message) { fail(message); }
        });
    }

    private static NativeHistoryTransfers.Observer observeThread(NativeThread thread) {
        return job -> {
            if (job.finished) thread.finishImport();
            else thread.pauseForImport();
        };
    }

    @Test public void pendingAttachmentWritesStayWithTheWorkspaceAfterItsScreenCloses() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            String owner = UUID.randomUUID().toString();
            String other = UUID.randomUUID().toString();
            NativeHistoryTransfers.writingAttachment(owner);
            try {
                assertNull(NativeHistoryTransfers.begin(owner, NativeHistoryTransfers.Kind.IMPORT));
                assertNull(NativeHistoryTransfers.begin(other, NativeHistoryTransfers.Kind.LEGACY));
                NativeHistoryTransfers.Job parallel = NativeHistoryTransfers.begin(other, NativeHistoryTransfers.Kind.IMPORT);
                assertNotNull(parallel);
                NativeHistoryTransfers.finish(parallel, false, "");
            } finally {
                NativeHistoryTransfers.finishedAttachment(owner);
            }
            NativeHistoryTransfers.Job ready = NativeHistoryTransfers.begin(owner, NativeHistoryTransfers.Kind.IMPORT);
            assertNotNull(ready);
            NativeHistoryTransfers.finish(ready, false, "");
        });
    }

    @Test public void replacementSurvivesClosingOldThreadAndSavingRestoredThreadBeforeReload() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String owner = UUID.randomUUID().toString();
            NativeHistoryStore store = new NativeHistoryStore(context, owner);
            JSONObject session = NativeAccountStore.object("id", "same-id", "title", "Before", "messages", new JSONArray());
            store.saveSession(session);
            store.setActiveId("same-id");
            NativeThread original = thread(store);
            original.draft("Pending local edit");
            NativeHistoryTransfers.Observer oldObserver = observeThread(original);
            NativeHistoryTransfers.observe(oldObserver);
            NativeHistoryTransfers.Job job = null;
            NativeHistoryTransfers.Observer newObserver = null;
            try {
                job = NativeHistoryTransfers.begin(owner, NativeHistoryTransfers.Kind.IMPORT);
                assertNotNull(job);
                assertTrue(NativeHistoryTransfers.importing(owner));
                assertFalse(NativeHistoryTransfers.importing(UUID.randomUUID().toString()));
                assertNull(NativeHistoryTransfers.begin(owner, NativeHistoryTransfers.Kind.IMPORT));
                assertNull(NativeHistoryTransfers.begin("default", NativeHistoryTransfers.Kind.LEGACY));
                assertEquals("Pending local edit", store.draft("same-id"));
                NativeHistoryTransfers.forget(oldObserver);
                NativeThread restored = thread(store);
                newObserver = observeThread(restored);
                NativeHistoryTransfers.observe(newObserver);
                assertSame(job, NativeHistoryTransfers.find(owner, job.id));
                NativeThread.put(session, "title", "Restored");
                store.saveSession(session);
                store.setDraft("same-id", "Imported draft");
                original.persist();
                original.close();
                restored.draft("stale input");
                restored.persist();
                assertEquals("Imported draft", store.draft("same-id"));
                NativeHistoryTransfers.finish(job, true, "Restored");
                assertEquals("Restored", restored.session().optString("title"));
                assertEquals("Imported draft", restored.draft());
                restored.setOption("thinking", "true");
                assertEquals("Restored", store.session("same-id").optString("title"));
                restored.close();
            } finally {
                NativeHistoryTransfers.forget(oldObserver);
                if (newObserver != null) NativeHistoryTransfers.forget(newObserver);
                if (job != null && !job.finished) NativeHistoryTransfers.finish(job, false, "");
                store.clearWorkspace();
            }
        });
    }
}
