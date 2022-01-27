package com.rokid.glass2cameratest

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.BindingAdapter
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.rokid.glass2cameratest.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {

    lateinit var dataBinding: ActivityMainBinding
    lateinit var cameraManager: CameraManager

    val data by lazy {
        InputData { type ->
            when (type) {
                1 -> {
                    lockFocus()
                    lifecycleScope.launch(Dispatchers.IO) {
                        capture().use {
                            sendToImage(it)
                        }
                    }
                }
            }
        }.apply {
            this.input1.observe(this@MainActivity, {
                Log.e("rokid", it)
            })
            this.input2.observe(this@MainActivity, {
                Log.e("rokid", it)
            })
        }
    }

    private fun sendToImage(result: CaptureResult) {
        val buffer = result.image.planes[0].buffer
        val array = ByteArray(buffer.capacity())
        buffer.get(array)
        data.image.postValue(BitmapFactory.decodeByteArray(array, 0, array.size, null))
    }

    private lateinit var imageReader: ImageReader
    private lateinit var camera: CameraDevice
    private lateinit var session: CameraCaptureSession

    private val imageReaderThread = HandlerThread("imageReaderThread").apply {
        start()
    }
    private val imageReaderHandle = Handler(imageReaderThread.looper)

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics("0")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataBinding = ActivityMainBinding.inflate(layoutInflater)
        dataBinding.lifecycleOwner = this

        setContentView(dataBinding.root)

        dataBinding.data = data
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        cameraManager.cameraIdList.forEach {
            Log.e("rokid", "cameraId == $it")
        }
        (cameraManager.getCameraCharacteristics("0")
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap).getOutputSizes(
            ImageFormat.JPEG
        )?.forEach {
            Log.e("rokid", "${it.width} * ${it.height}")
        }

        dataBinding.tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                dataBinding.root.post { initializeCamera() }
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
//                TODO("Not yet implemented"
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
//                TODO("Not yet implemented")
                return true
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
//                TODO("Not yet implemented")
            }

        }

    }

    private lateinit var captureRequest: CaptureRequest.Builder
    private lateinit var targets: List<Surface>

    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        camera = openCamera(cameraManager, "0", cameraHandler)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxByOrNull {
                it.height * it.width
            }!!

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)

        targets = listOf(Surface(dataBinding.tv.surfaceTexture), imageReader.surface)

        session = createCaptureSession(camera, targets, cameraHandler)

        captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(targets[0])
            //加入自动对焦
//            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

    }


    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine {
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(p0: CameraCaptureSession) {
                it.resume(p0)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                it.resumeWithException(RuntimeException("Camera ${device.id}  session configuration failed"))
            }

        }, handler)
    }


    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) = cont.resume(p0)

            override fun onDisconnected(p0: CameraDevice) {
                finish()
            }

            override fun onError(p0: CameraDevice, p1: Int) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("camera $cameraId error: ($p1)0"))
            }

        }, handler)
    }

    private suspend fun capture(): CaptureResult = suspendCoroutine { cont ->
        while (imageReader.acquireLatestImage() != null) {
        }

        val imageQueue = ArrayBlockingQueue<Image>(3)
        imageReader.setOnImageAvailableListener({
            val i = it.acquireNextImage()
            imageQueue.add(i)
        }, imageReaderHandle)

        val captureRequest =
            session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                //拍照request 不用再次申请对焦
//                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
//                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                Log.e(
                    "rokid", "开始拍照${
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss-SSS", Locale.CHINA).format(
                            Date(System.currentTimeMillis())
                        )
                    }"
                )
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                unlockFocus()
                lifecycleScope.launch(cont.context) {
                    while (true){
                        val image = imageQueue.take()
                        imageReader.setOnImageAvailableListener(null, null)
                        while (imageQueue.size > 0 ){
                            imageQueue.take().close()
                        }

                        cont.resume(CaptureResult(image, imageReader.imageFormat))
                    }
                }
            }

        }, cameraHandler)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.e("rokid", "keycode == $keyCode")
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.e("rokid", "keycode == $keyCode")
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraThread.quitSafely()
            imageReaderThread.quitSafely()
        }catch (e: Exception){}
    }

    private fun lockFocus(){
        captureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        session.capture(captureRequest.build(), null, cameraHandler)
    }
    private fun unlockFocus(){
        captureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        session.capture(captureRequest.build(), null, cameraHandler)
        session.setRepeatingRequest(captureRequest.apply {
            addTarget(targets[0])
            //加入自动对焦
//            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }.build(), null, cameraHandler)
    }
}

data class CaptureResult(val image: Image, val format: Int) : Closeable {
    override fun close() = image.close()
}

data class InputData(
    val input1: MutableLiveData<String> = MutableLiveData(),
    val input2: MutableLiveData<String> = MutableLiveData(),
    val image: MutableLiveData<Bitmap> = MutableLiveData(),
    val action: (Int) -> Unit
) {
    fun on1Clicked(v: View) {
        action(1)
    }
}

object Binding {
    @JvmStatic
    @BindingAdapter("loadImage")
    fun setImage(v: View, bitmap: Bitmap?) {
        if (v is ImageView) {
            bitmap?.let {
                v.setImageBitmap(it)
            }
        }
    }
}