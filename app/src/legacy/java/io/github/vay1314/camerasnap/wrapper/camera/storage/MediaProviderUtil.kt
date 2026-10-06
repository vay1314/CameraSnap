package io.github.vay1314.camerasnap.wrapper.camera.storage

import android.content.Context
import android.net.Uri
import io.github.vay1314.camerasnap.dexkit.CameraMembers

object MediaProviderUtil {
    fun getContentUriFromPath(context: Context, path: String) =
        CameraMembers.OtherMembers.mGetContentUriFromPath.invoke(null, context, path) as Uri
}