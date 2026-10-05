package com.vay.camerasnap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
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
        if (!system && uid != Process.myUid() && !isCamera(uid)) {
            throw new SecurityException("Only the module, system and system camera may access configuration");
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

    private boolean isCamera(int uid) {
        try {
            ApplicationInfo info = getContext().getPackageManager()
                    .getApplicationInfo("com.android.camera", 0);
            return info.uid == uid && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return false; }
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
