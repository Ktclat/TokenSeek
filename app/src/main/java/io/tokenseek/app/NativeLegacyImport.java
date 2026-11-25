package io.tokenseek.app;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Imports only completed private snapshots from the separately installed legacy exporter. */
final class NativeLegacyImport {
    private static final String MIGRATIONS = "legacy-handoff-v1";
    private static final String RECEIPTS = "legacy-handoff-receipts-v1";
    private NativeLegacyImport() {}

    private static String selected(File root) throws IOException {
        AtomicFile pointer = new AtomicFile(new File(root,"current"));
        try {
            String id = new String(pointer.readFully(),StandardCharsets.US_ASCII);
            if (!id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                throw new IOException("Invalid migration snapshot");
            return id;
        } catch(FileNotFoundException absent) {
            return null;
        }
    }
    static boolean completed(Context context) {
        try {
            String id = selected(new File(context.getFilesDir(), MIGRATIONS));
            return id != null && context.getSharedPreferences(RECEIPTS, Context.MODE_PRIVATE).getBoolean(id, false);
        } catch (IOException unreadable) {
            return false;
        }
    }
    static boolean pending(Context context) {
        try {
            String id = selected(new File(context.getFilesDir(),MIGRATIONS));
            return id != null && !context.getSharedPreferences(RECEIPTS,Context.MODE_PRIVATE).getBoolean(id,false);
        } catch(IOException unreadable) {
            return true; // Offer retry; never silently present a damaged snapshot as an empty history.
        }
    }
    private static JSONObject json(File file) throws IOException,JSONException {
        long max=Runtime.getRuntime().maxMemory()/4;
        if(!file.isFile() || file.length()>max)throw new IOException("Cannot read migration metadata");
        try(InputStream stream=new FileInputStream(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;
            while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);
            return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    private static void verify(File file,JSONObject entry) throws IOException,JSONException {
        if(!file.isFile() || file.length()!=entry.getLong("size"))throw new IOException("Missing migration record");
        try(FileInputStream input=new FileInputStream(file)){
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);
            if(!Base64.encodeToString(hash.digest(),Base64.NO_WRAP).equals(entry.getString("sha256")))
                throw new IOException("Damaged migration record");
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static final class Workspace {
        final NativeHistoryStore history;
        final Set<String> all = new HashSet<>(), added = new HashSet<>();
        JSONObject organization, resume;
        Workspace(Context context,String id) {history=new NativeHistoryStore(context,id);}
    }
    /** Call on a worker. Existing native IDs, drafts, project names and settings win on conflicts. */
    static synchronized int merge(Context context,NativeAccountStore accounts) throws IOException {
        File root=new File(context.getFilesDir(),MIGRATIONS);
        String selected=selected(root);
        if(selected==null || context.getSharedPreferences(RECEIPTS,Context.MODE_PRIVATE).getBoolean(selected,false)) return 0;
        File snapshot=new File(root,selected);
        NativeHistoryStore anchor=new NativeHistoryStore(context,"default");
        try {
            JSONObject meta=json(new File(snapshot,"index.json"));
            if(meta.getInt("version")!=1)throw new IOException("Unsupported migration version");
            JSONArray entries=meta.getJSONArray("entries");
            Map<String,Workspace> profiles=new HashMap<>();
            JSONObject directory=null,settings=null;
            Set<String> unique=new HashSet<>();
            for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.getJSONObject(i);
                String owner=entry.getString("owner"),kind=entry.getString("kind"),id=entry.getString("id");
                if(!unique.add(new JSONArray().put(owner).put(kind).put(id).toString()))throw new IOException("Duplicate migration record");
                File file=new File(snapshot,Integer.toString(i));verify(file,entry);
                if(owner.isEmpty()){
                    if(!id.isEmpty())throw new IOException("Invalid global metadata");
                    if("directory".equals(kind))directory=json(file);
                    else if("settings".equals(kind))settings=json(file);
                    else throw new IOException("Invalid global record");
                }
            }
            if(directory==null || settings==null)throw new IOException("Incomplete migration snapshot");
            JSONArray rows=directory.getJSONArray("profiles");
            for(int i=0;i<rows.length();i++){
                JSONObject p=rows.getJSONObject(i);String id=p.getString("id");
                if(!NativeAccountStore.validId(id) || profiles.put(id,new Workspace(context,id))!=null ||
                        p.getString("username").trim().isEmpty())throw new IOException("Invalid migration profile");
            }
            if(!profiles.containsKey(directory.getString("active")))throw new IOException("Unknown active profile");
            anchor.db.beginTransaction();
            int imported=0;
            try {
                for(int i=0;i<entries.length();i++){
                    JSONObject entry=entries.getJSONObject(i);
                    String owner=entry.getString("owner"),kind=entry.getString("kind"),id=entry.getString("id");
                    if(owner.isEmpty())continue;
                    Workspace profile=profiles.get(owner);
                    if(profile==null)throw new IOException("Unknown migration profile");
                    File file=new File(snapshot,Integer.toString(i));
                    switch(kind){
                        case "session": {
                            JSONObject session=json(file);
                            if(!id.equals(session.getString("id")))throw new IOException("Session ID mismatch");
                            profile.all.add(id);
                            if(!profile.history.has("session",id)){
                                profile.history.saveSession(session);profile.added.add(id);imported++;
                            }
                            break;
                        }
                        case "file":
                            if(!profile.history.hasAttachment(id))try(InputStream input=new FileInputStream(file)){
                                profile.history.saveAttachment(id,input);
                            }
                            break;
                        case "organization": profile.organization=json(file);break;
                        case "resume": profile.resume=json(file);break;
                        default:throw new IOException("Unknown migration record type");
                    }
                }
                for(Workspace profile:profiles.values()){
                    JSONObject org=NativeHistoryArchive.organization(profile.organization,profile.all,false);
                    profile.history.saveOrganization(NativeHistoryArchive.mergedOrganization(profile.history.organization(),org,profile.added));
                    NativeHistoryArchive.restoreResume(profile.history,profile.resume,profile.all,false);
                }
                if(!accounts.importLegacy(directory,settings))throw new IOException("Could not save migrated account directory");
                anchor.db.setTransactionSuccessful();
            } finally {anchor.db.endTransaction();}
            if(!context.getSharedPreferences(RECEIPTS,Context.MODE_PRIVATE).edit().putBoolean(selected,true).commit())
                throw new IOException("Could not record completed migration");
            // Keep the receipt and pointer. The original IndexedDB is untouched, and the staging copy is no longer needed.
            File[] done=snapshot.listFiles();
            if(done!=null){for(File file:done)file.delete();snapshot.delete();}
            return imported;
        }catch(JSONException | IllegalArgumentException error){throw new IOException("Invalid migration snapshot",error);}
    }
}
