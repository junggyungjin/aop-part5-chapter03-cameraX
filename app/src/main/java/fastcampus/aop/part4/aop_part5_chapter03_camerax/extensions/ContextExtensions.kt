package fastcampus.aop.part4.aop_part5_chapter03_camerax.extensions

import android.content.Context
import fastcampus.aop.part4.aop_part5_chapter03_camerax.R
import java.io.File

internal fun Context.getAppCachePictureDirectory() : File {
    val mediaDir = filesDir?.let {
        File(it, getString(R.string.app_name)).apply { mkdirs() }
    }
    return if (mediaDir != null && mediaDir.exists()) {
        mediaDir
    } else {
        filesDir
    }
}
