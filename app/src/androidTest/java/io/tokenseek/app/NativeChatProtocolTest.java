package io.tokenseek.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeChatProtocolTest {
    static String event(String value) { return "data: " + value + "\r\n\r\n"; }

    @Test public void streamedThinkingTextCitationsAndPartialUsageAreMergedInProtocolOrder() throws Exception {
        String start = "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5,\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":31,\"output_tokens\":0}}}";
        String[] events = {
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"考虑输入\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sample\"}}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"你\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"title\":\"示例\",\"url\":\"https://example.org/\"}}}",
                "{\"type\":\"message_delta\",\"delta\":{\"title\":\"测试\",\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":8}}",
                "{\"type\":\"message_stop\"}"};
        StringBuilder frames = new StringBuilder("\uFEFF: heartbeat\r\n\r\n").append(event(start));
        for (String e : events) frames.append(event(e));
        NativeChatResponse reply = new NativeChatResponse();
        List<String> seen = new ArrayList<>();
        reply.stream(new StringReader(frames.toString()), () -> seen.add(reply.result("streaming", "").optString("usageKind")));
        JSONObject answer = reply.result("done", "");
        assertEquals("initial",seen.get(0));
        assertEquals("final",answer.getString("usageKind"));
        assertEquals(31,answer.getJSONObject("usage").getInt("cache_read_input_tokens"));
        assertEquals(8,answer.getJSONObject("usage").getInt("output_tokens"));
        assertEquals("考虑输入",answer.getString("thinking"));
        assertEquals("你好",answer.getString("text"));
        assertEquals("示例",answer.getJSONArray("blocks").getJSONObject(1).getJSONArray("citations").getJSONObject(0).getString("title"));
        assertEquals("测试",answer.getString("title"));
    }

    @Test public void disconnectOrServerErrorPreservesPartialDataAndNeverInventsFinalUsage() throws Exception {
        NativeChatResponse partial = new NativeChatResponse();
        String frame=event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"半句\"}}");
        assertThrows(IOException.class, () -> partial.stream(new StringReader(frame), () -> {}));
        assertEquals("半句",partial.result("error","").getString("text"));
        assertTrue(partial.result("error","").isNull("usage"));
        String failure=event("{\"type\":\"error\",\"error\":{\"message\":\"unavailable\"}}");
        assertEquals("unavailable",assertThrows(IOException.class, () -> partial.stream(new StringReader(failure), () -> {})).getMessage());
        NativeChatResponse refused = new NativeChatResponse();
        refused.message(new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"partial\"}],\"stop_reason\":\"refusal\"}"));
        assertEquals("",refused.result("done","").getString("text"));
    }

    @Test public void modelControlsAndFileReferencesUseTheOfficialMessagesProtocol() throws Exception {
        JSONArray path = new JSONArray("[{\"role\":\"user\",\"content\":\"hi\",\"files\":[{\"serverId\":\"file-sample\",\"isImage\":true}]},{\"role\":\"assistant\",\"status\":\"pending\"}]");
        JSONArray messages=NativeChatClient.history(path);
        JSONObject flash=NativeChatClient.body(NativeChatClient.FLASH,true,true,"",messages,true);
        assertEquals(2,flash.getJSONArray("tools").length());
        assertEquals("enabled",flash.getJSONObject("thinking").getString("type"));
        assertEquals("file-sample",messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("source").getString("file_id"));
        assertThrows(IOException.class, () -> NativeChatClient.body(NativeChatClient.PRO,true,false,"",messages,true));
        JSONObject pro=NativeChatClient.body(NativeChatClient.PRO,false,true,"",new JSONArray("[{\"role\":\"user\",\"content\":\"hi\"}]"),false);
        assertFalse(pro.has("tools"));assertFalse(pro.getBoolean("stream"));
    }

    @Test public void failedRoundsAndHistoricalThinkingAreOmittedWhileContinuationKeepsTheSignature() throws Exception {
        JSONArray path=new JSONArray("[{\"role\":\"user\",\"content\":\"old\"},{\"role\":\"assistant\",\"status\":\"error\",\"text\":\"partial\"},{\"role\":\"user\",\"content\":\"new\"},{\"role\":\"assistant\",\"status\":\"stopped\",\"blocks\":[{\"type\":\"thinking\",\"thinking\":\"想\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":\"答\"}]}]");
        JSONArray history=NativeChatClient.history(path);
        assertEquals(2,history.length());assertEquals(1,history.getJSONObject(1).getJSONArray("content").length());
        JSONArray resume=NativeChatClient.history(path,true,true);
        assertEquals("sig",resume.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"));
    }

    @Test public void requiredHistoricalAndCurrentFileIdsAreNeverSilentlyDiscarded() throws Exception {
        JSONObject historical = new JSONObject().put("id", "old").put("name", "历史.pdf").put("serverId", JSONObject.NULL);
        JSONObject current = new JSONObject().put("id", "new").put("name", "新附件.txt");
        JSONArray path = new JSONArray()
                .put(new JSONObject().put("role", "user").put("files", new JSONArray().put(historical)))
                .put(new JSONObject().put("role", "assistant").put("status", "done").put("text", "答复"))
                .put(new JSONObject().put("role", "user").put("files", new JSONArray().put(current)));
        List<JSONObject> missing = NativeChatHistory.missingFiles(path, false, false);
        assertEquals(2, missing.size());
        assertSame(historical, missing.get(0)); assertSame(current, missing.get(1));
        assertTrue(assertThrows(org.json.JSONException.class, () -> NativeChatClient.history(path)).getMessage().contains("历史.pdf"));
    }
}
