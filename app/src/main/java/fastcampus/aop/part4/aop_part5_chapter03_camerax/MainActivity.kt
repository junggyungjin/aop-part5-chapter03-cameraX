package fastcampus.aop.part4.aop_part5_chapter03_camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FocusFinder
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
import androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
import androidx.camera.core.impl.ImageOutputConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import com.google.common.util.concurrent.ListenableFuture
import fastcampus.aop.part4.aop_part5_chapter03_camerax.databinding.ActivityMainBinding
import fastcampus.aop.part4.aop_part5_chapter03_camerax.extensions.loadCenterCrop
import fastcampus.aop.part4.aop_part5_chapter03_camerax.util.PathUtil
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private lateinit var cameraExecutor: ExecutorService
    private val cameraMainExecutor by lazy { ContextCompat.getMainExecutor(this) }

    private val cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(this) } // 카메라 얻어오면 이후 실행 리스너 등록
//    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private lateinit var imageCapture: ImageCapture


    private val displayManer by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private var displayId: Int = -1

    private var camera: Camera? = null
    private var root: View? = null

    private var isCapturing: Boolean = false

    private var isFlashEnabled: Boolean = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        // 화면 회전시 대응하는 코드
        @SuppressLint("RestrictedApi")
        override fun onDisplayChanged(displayId: Int) {
            if (this@MainActivity.displayId == displayId) {
                if (::imageCapture.isInitialized && root != null) {
                    imageCapture.targetRotation =
                        root?.display?.rotation ?: ImageOutputConfig.INVALID_ROTATION
                }
            }
        }

    }

    private var uriList = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = binding.root
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera(binding.viewFinder)
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera(viewFinder: PreviewView) {
        displayManer.registerDisplayListener(displayListener, null)
        cameraExecutor = Executors.newSingleThreadExecutor()
        viewFinder.postDelayed({
            displayId = viewFinder.display.displayId
            bindCameraUseCase()
        }, 10)
    }

    private fun bindCameraUseCase() = with(binding) {
        val rotation = viewFinder.display.rotation
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(LENS_FACING).build() // 카메라 설정(후면)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().apply {
                setTargetAspectRatio(AspectRatio.RATIO_4_3)
                setTargetRotation(rotation)
            }.build()

            // 카메라 캡쳐 기능
            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)

            imageCapture = imageCaptureBuilder.build()

            try {
                cameraProvider.unbindAll() // 기존에 바인딩 되어 있는 카메라는 해제를 해준다
                camera = cameraProvider.bindToLifecycle(
                    this@MainActivity, cameraSelector, preview, imageCapture
                )
                preview.setSurfaceProvider(viewFinder.surfaceProvider)
                bindCaptureListener()
                bindZoomListener()
                initFlashAndAddListener()
                bindPreviewImageViewClickListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, cameraMainExecutor)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindZoomListener() = with(binding) {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(this@MainActivity, listener)
        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            return@setOnTouchListener true
        }
    }

    private fun initFlashAndAddListener() = with(binding) {
        var hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        flashSwitch.isGone = hasFlash.not()
        if (hasFlash) {
            flashSwitch.setOnCheckedChangeListener { _, isChecked ->
                isFlashEnabled = isChecked
            }
        } else {
            // 방어코드
            isFlashEnabled = false
            flashSwitch.setOnCheckedChangeListener(null)
        }
    }

    private fun bindCaptureListener() = with(binding) {
        captureButton.setOnClickListener {
            // 클릭하는 시점에 현재 캡쳐중인지 아닌지를 판단을 해서 캡쳐할지 안할지 결정
            if (isCapturing.not()) {
                isCapturing = true
                captureCamera()
            }
        }
    }

    private fun updateSavedImageContent() {
        contentUri?.let {
            isCapturing = try {
                var file = File(PathUtil.getPath(this, it) ?: throw FileNotFoundException())
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.path),
                    arrayOf("image/jpeg"),
                    null
                )
                Handler(Looper.getMainLooper()).post {
                    binding.previewImageView.loadCenterCrop(url = it.toString(), corner = 4f)
                }
                uriList.add(it)
                flashLight(false)
                false
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "파일이 존재하지 않습니다", Toast.LENGTH_SHORT).show()
                flashLight(false)
                false
            }
        }
    }

    private var contentUri: Uri? = null

    private fun captureCamera() {
        if (::imageCapture.isInitialized.not()) return
        val photoFile = File(
            PathUtil.getOutputDirectory(this),
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.KOREA
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        if (isFlashEnabled) flashLight(true)
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    contentUri = savedUri
                    updateSavedImageContent()

                }

                override fun onError(e: ImageCaptureException) {
                    e.printStackTrace()
                    isCapturing = false
                    flashLight(false)
                }
            })
    }

    private fun flashLight(light: Boolean) {
        // 방어 코드
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        if (hasFlash) {
            camera?.cameraControl?.enableTorch(light)
        }
    }

    private fun bindPreviewImageViewClickListener() = with(binding) {
        previewImageView.setOnClickListener {
            startActivity(
                ImageListActivity.newIntent(this@MainActivity, uriList)
            )
        }
    }


//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//
//        if (requestCode == REQUEST_CODE_PERMISSIONS) {
//            if (allPermissionsGranted()) {
//                startCamera(binding.viewFinder)
//            } else {
//                Toast.makeText(this, "카메라 권한이 없습니다.", Toast.LENGTH_SHORT).show()
//                finish()
//            }
//        }
//    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera(binding.viewFinder)
            } else {
                Toast.makeText(this, "카메라 권한이 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private val LENS_FACING: Int = CameraSelector.LENS_FACING_BACK
    }
}