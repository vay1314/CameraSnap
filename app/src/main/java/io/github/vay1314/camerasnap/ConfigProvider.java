package io.github.vay1314.camerasnap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        boolean system = uid == Process.SYSTEM_UID;
        if (!isTrustedCaller(uid, Process.myUid())) {
            throw new SecurityException("Only the module and system may access configuration");
        }
        if ("get".equals(method)) return SnapConfig.read(getContext()).toBundle();
        if ("system_ready".equals(method) && system) {
            SnapConfig.prefs(getContext()).edit()
                    .putLong("system_ready_at", System.currentTimeMillis())
                    .putString("system_hook", arg == null ? "" : arg).apply();
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("Unknown operation: " + method);
    }

    static boolean isTrustedCaller(int uid, int moduleUid) {
        return uid == Process.SYSTEM_UID || uid == moduleUid;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
