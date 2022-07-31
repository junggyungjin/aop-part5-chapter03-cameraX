package fastcampus.aop.part4.aop_part5_chapter03_camerax

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

class AopPart5Chapter03Application: Application(), CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig {

        return Camera2Config.defaultConfig()
    }

}