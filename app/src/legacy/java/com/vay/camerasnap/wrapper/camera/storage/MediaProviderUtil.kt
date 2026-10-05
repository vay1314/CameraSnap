package com.vay.camerasnap.wrapper.camera.storage

import android.content.Context
import android.net.Uri
import com.vay.camerasnap.dexkit.CameraMembers

object MediaProviderUtil {
    fun getContentUriFromPath(context: Context, path: String) =
        CameraMembers.OtherMembers.mGetContentUriFromPath.invoke(null, context, path) as Uri
}