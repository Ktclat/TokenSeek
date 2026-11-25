package io.tokenseek.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Separate, offline, user-launched exporter. Its origin matches the app-bundled 0.9.2 chat client. */
public final class LegacyExportActivity extends Activity {
    private static final String SITE = "https://appassets.androidplatform.net/site/";
    private WebView page;
    private Export writer;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        page = new WebView(this);
        setContentView(page);
        WebSettings settings=page.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setBlockNetworkLoads(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req){ return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req){
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        writer=new Export(new File(getFilesDir(),"legacy-handoff-v1"));
        page.addJavascriptInterface(writer,"LegacyTransfer");
        // HTTPS base sets the original chat storage origin without fetching a main document.
        // WebView providers on devices without INTERNET permission can block requests before interception.
        try {
            String script = asset("migration.js");
            String hash=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            String document = asset("index.html").replace("script-src 'self'", "script-src 'sha256-"+hash+"'")
                    .replace("<script src=\"migration.js\"></script>", "<script>"+script+"</script>");
            page.loadDataWithBaseURL(SITE+"index.html", document, "text/html", "UTF-8", null);
        } catch(Exception failure) {
            android.widget.TextView error=new android.widget.TextView(this);
            error.setText("无法读取迁移工具，请重新安装迁移版。");setContentView(error);
        }
    }
    private String asset(String filename) throws IOException {
        try(InputStream input=getAssets().open(filename); ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    @Override public void onDestroy(){
        page.removeJavascriptInterface("LegacyTransfer");page.destroy();writer.cancel();super.onDestroy();
    }

    static final class Export {
        final File root;
        File work;
        FileOutputStream out;
        MessageDigest digest;
        long expected, written;
        JSONObject record;
        JSONArray entries;
        Export(File root){this.root=root;}
        @JavascriptInterface public synchronized boolean start(){
            cancel();
            if(!root.isDirectory() && !root.mkdirs())return false;
            work=new File(root,UUID.randomUUID().toString());
            entries=new JSONArray();
            return work.mkdir();
        }
        @JavascriptInterface public synchronized boolean begin(String owner,String kind,String id,String size){
            try{
                if(work==null || out!=null || id==null || !kind.matches("directory|settings|session|file|organization|resume") ||
                        !owner.matches("|default|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))return false;
                expected=Long.parseLong(size);written=0;
                if(expected<0)return false;
                record=new JSONObject().put("owner",owner).put("kind",kind).put("id",id).put("size",expected);
                digest=MessageDigest.getInstance("SHA-256");
                out=new FileOutputStream(new File(work,Integer.toString(entries.length())));
                return true;
            }catch(Exception e){return false;}
        }
        @JavascriptInterface public synchronized boolean append(String base64){
            try{
                if(out==null || base64==null || base64.length()>44000)return false;
                byte[] chunk=Base64.decode(base64,Base64.NO_WRAP);
                if(chunk.length>expected-written)return false;
                out.write(chunk);digest.update(chunk);written+=chunk.length;return true;
            }catch(Exception e){return false;}
        }
        @JavascriptInterface public synchronized boolean endRecord(){
            try{
                if(out==null || written!=expected)return false;
                out.getFD().sync();out.close();out=null;
                entries.put(record.put("sha256",Base64.encodeToString(digest.digest(),Base64.NO_WRAP)));
                return true;
            }catch(Exception e){return false;}
        }
        @JavascriptInterface public synchronized boolean publish(){
            if(work==null || out!=null)return false;
            AtomicFile pointer=new AtomicFile(new File(root,"current"));
            FileOutputStream slot=null;
            try(FileOutputStream manifest=new FileOutputStream(new File(work,"index.json"))){
                manifest.write(new JSONObject().put("version",1).put("entries",entries).toString().getBytes(StandardCharsets.UTF_8));
                manifest.getFD().sync();
                slot=pointer.startWrite();slot.write(work.getName().getBytes(StandardCharsets.US_ASCII));pointer.finishWrite(slot);
                work=null;entries=null;return true;
            }catch(Exception e){if(slot!=null)pointer.failWrite(slot);return false;}
        }
        @JavascriptInterface public synchronized void cancel(){
            if(out!=null){try{out.close();}catch(IOException ignored){}out=null;}
            if(work!=null){File[] files=work.listFiles();if(files!=null)for(File f:files)f.delete();work.delete();work=null;}
        }
    }
}
