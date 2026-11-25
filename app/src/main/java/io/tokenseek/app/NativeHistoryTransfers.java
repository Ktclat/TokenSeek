package io.tokenseek.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main-thread registry. A worker reports completion here only after its database transaction ends. */
final class NativeHistoryTransfers {
    enum Kind { IMPORT, EXPORT, LEGACY }
    static final class Job {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final Kind kind;
        boolean finished, changed;
        String message = "";

        Job(String owner, Kind kind) { this.owner = owner; this.kind = kind; }
        boolean affects(String workspace) { return kind == Kind.LEGACY || owner.equals(workspace); }
    }

    interface Observer {
        default boolean allowsImport(Job job) { return true; }
        void changed(Job job);
    }

    private static final Set<Observer> observers = new LinkedHashSet<>();
    private static final Map<String, Job> latest = new HashMap<>();
    private static final Map<String, Integer> attachmentWrites = new HashMap<>();
    private static final String ALL = "*";

    private NativeHistoryTransfers() {}
    static void observe(Observer observer) { observers.add(observer); }
    static void forget(Observer observer) { observers.remove(observer); }
    static Job active(String owner) {
        Job global = latest.get(ALL);
        if (global != null && !global.finished) return global;
        Job local = latest.get(owner);
        return local == null || local.finished ? null : local;
    }
    static Job find(String owner, String id) {
        if (id == null) return null;
        for (Job job : latest.values()) if (job.affects(owner) && job.id.equals(id)) return job;
        return null;
    }
    static boolean importing(String owner) {
        Job job = active(owner);
        return job != null && job.kind != Kind.EXPORT;
    }
    static void writingAttachment(String owner) {
        attachmentWrites.put(owner, attachmentWrites.getOrDefault(owner, 0) + 1);
    }
    static void finishedAttachment(String owner) {
        int count = attachmentWrites.getOrDefault(owner, 0);
        if (count <= 1) attachmentWrites.remove(owner);
        else attachmentWrites.put(owner, count - 1);
    }
    static Job begin(String owner, Kind kind) {
        Job next = new Job(owner, kind);
        if (kind != Kind.EXPORT && (kind == Kind.LEGACY ? !attachmentWrites.isEmpty() : attachmentWrites.containsKey(owner)))
            return null;
        for (Job job : latest.values())
            if (!job.finished && (kind == Kind.LEGACY || job.affects(owner))) return null;
        if (kind != Kind.EXPORT)
            for (Observer observer : observers) if (!observer.allowsImport(next)) return null;
        latest.put(kind == Kind.LEGACY ? ALL : owner, next);
        publish(next);
        return next;
    }
    static void finish(Job job, boolean changed, String message) {
        if (job.finished) return;
        job.finished = true;
        job.changed = changed;
        job.message = message;
        publish(job);
    }
    private static void publish(Job job) {
        for (Observer observer : new ArrayList<>(observers)) if (observers.contains(observer)) observer.changed(job);
    }
}
