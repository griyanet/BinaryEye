package de.markusfisch.android.binaryeye.activity

import com.google.zxing.Result

import de.markusfisch.android.cameraview.widget.CameraView

import de.markusfisch.android.binaryeye.app.initSystemBars
import de.markusfisch.android.binaryeye.rs.YuvToGray
import de.markusfisch.android.binaryeye.zxing.Zxing
import de.markusfisch.android.binaryeye.R

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast

class CameraActivity : AppCompatActivity() {
	companion object {
		private val REQUEST_CAMERA = 1
	}

	private val zxing = Zxing()
	private val decodingRunnable = Runnable {
		while (decoding) {
			val result = decodeFrame()
			if (result != null) {
				cameraView.post { found(result) }
				decoding = false
				break
			}
		}
	}

	private lateinit var vibrator: Vibrator
	private lateinit var cameraView: CameraView
	private lateinit var zoomBar: SeekBar
	private lateinit var yuvToGray: YuvToGray

	private var decoding = false
	private var decodingThread: Thread? = null
	private var frameData: ByteArray? = null
	private var frameWidth: Int = 0
	private var frameHeight: Int = 0
	private var frameOrientation: Int = 0
	private var odd = false
	private var flash = false

	override fun onRequestPermissionsResult(
			requestCode: Int,
			permissions: Array<String>,
			grantResults: IntArray) {
		when (requestCode) {
			REQUEST_CAMERA -> if (grantResults.size > 0 &&
					grantResults[0] != PackageManager.PERMISSION_GRANTED) {
				Toast.makeText(this,
						R.string.no_camera_no_fun,
						Toast.LENGTH_SHORT).show()
				finish()
			}
		}
	}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_camera)
		initSystemBars(this)
		setSupportActionBar(findViewById(R.id.toolbar) as Toolbar)

		yuvToGray = YuvToGray(this)
		vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

		cameraView = findViewById(R.id.camera_view) as CameraView
		zoomBar = findViewById(R.id.zoom) as SeekBar

		initCameraView()
		initZoomBar()
		initFlashFab(findViewById(R.id.flash))
	}

	override fun onDestroy() {
		super.onDestroy()
		yuvToGray.destroy()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleSendText(intent)
	}

	override fun onResume() {
		super.onResume()
		System.gc()
		cameraView.openAsync(CameraView.findCameraId(
				Camera.CameraInfo.CAMERA_FACING_BACK))
		startDecoding()
	}

	override fun onPause() {
		super.onPause()
		cancelDecoding()
		cameraView.close()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.activity_camera, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.create -> {
				startActivity(MainActivity.getEncodeIntent(this))
				true
			}
			R.id.info -> {
				openReadme()
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun openReadme() {
		startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
				"https://github.com/markusfisch/BinaryEye/blob/master/README.md")))
	}

	private fun handleSendText(intent: Intent) {
		if (!Intent.ACTION_SEND.equals(intent.getAction()) ||
				!"text/plain".equals(intent.getType())) {
			return
		}

		var text = intent.getStringExtra(Intent.EXTRA_TEXT)
		if (text.isEmpty()) {
			return
		}

		// consume this intent
		intent.setAction(null)

		startActivity(MainActivity.getEncodeIntent(this, text))
	}

	private fun checkPermissions() {
		val permission = android.Manifest.permission.CAMERA

		if (ContextCompat.checkSelfPermission(this, permission) !=
				PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, arrayOf(permission),
					REQUEST_CAMERA)
		}
	}

	private fun initCameraView() {
		cameraView.setOnCameraListener(object : CameraView.OnCameraListener {
			override fun onConfigureParameters(
					parameters: Camera.Parameters) {
				if (parameters.isZoomSupported()) {
					val max = parameters.getMaxZoom()
					zoomBar.max = max
					zoomBar.progress = max / 2
					parameters.setZoom(zoomBar.progress)
				} else {
					zoomBar.visibility = View.GONE
				}
				for (mode in parameters.getSupportedSceneModes()) {
					if (mode.equals(Camera.Parameters.SCENE_MODE_BARCODE)) {
						parameters.setSceneMode(mode)
						break
					}
				}
				CameraView.setAutoFocus(parameters)
			}

			override fun onCameraError(camera: Camera) {
				Toast.makeText(this@CameraActivity, R.string.camera_error,
						Toast.LENGTH_SHORT).show()
				finish()
			}

			override fun onCameraStarted(camera: Camera) {
				frameWidth = cameraView.frameWidth
				frameHeight = cameraView.frameHeight
				frameOrientation = cameraView.frameOrientation
				camera.setPreviewCallback { data, _ -> frameData = data }
			}

			override fun onCameraStopping(camera: Camera) {
				camera.setPreviewCallback(null)
			}
		})
	}

	private fun initZoomBar() {
		zoomBar.setOnSeekBarChangeListener(object :
				SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar, progress: Int,
					fromUser: Boolean) {
				setZoom(progress)
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) {}

			override fun onStopTrackingTouch(seekBar: SeekBar) {}
		})
	}

	private fun setZoom(zoom: Int) {
		val camera: Camera? = cameraView.getCamera()
		camera?.let {
			try {
				val params = camera.getParameters()
				params.setZoom(zoom)
				camera.setParameters(params)
			} catch (e: RuntimeException) {
				// ignore; there's nothing we can do
			}
		}
	}

	private fun initFlashFab(fab: View) {
		if (!packageManager.hasSystemFeature(
				PackageManager.FEATURE_CAMERA_FLASH)) {
			fab.visibility = View.GONE
		} else {
			fab.setOnClickListener { _ ->
				toggleTorchMode()
			}
		}
	}

	private fun toggleTorchMode() {
		val camera = cameraView.getCamera()
		val parameters = camera?.getParameters()
		parameters?.setFlashMode(if (flash)
			Camera.Parameters.FLASH_MODE_OFF
		else
			Camera.Parameters.FLASH_MODE_TORCH)
		flash = flash xor true
		parameters?.let { camera.setParameters(parameters) }
	}

	private fun startDecoding() {
		frameData = null
		decoding = true
		decodingThread = Thread(decodingRunnable)
		decodingThread?.start()
	}

	private fun cancelDecoding() {
		decoding = false
		if (decodingThread != null) {
			var retry = 100
			while (retry-- > 0) {
				try {
					decodingThread?.join()
					break
				} catch (e: InterruptedException) {
					// try again
				}
			}
		}
	}

	private fun decodeFrame(): Result? {
		frameData ?: return null
		return zxing.decodeBitmap(yuvToGray.convert(
				frameData!!, frameWidth, frameHeight, frameOrientation))
	}

	private fun found(result: Result) {
		cancelDecoding()
		vibrator.vibrate(100)

		startActivity(MainActivity.getDecodeIntent(this, result.text,
				result.getBarcodeFormat()))
	}
}