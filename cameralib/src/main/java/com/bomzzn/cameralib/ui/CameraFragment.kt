package com.bomzzn.cameralib.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
import androidx.camera.core.impl.utils.Exif
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.bomzzn.cameralib.CameraXImagePicker.Companion.KEY_CAMERA_CAPTURE_FORCE
import com.bomzzn.cameralib.CameraXImagePicker.Companion.KEY_FILENAME
import com.bomzzn.cameralib.CameraXImagePicker.Companion.KEY_FRONT_CAMERA
import com.bomzzn.cameralib.CameraXImagePicker.Companion.KEY_IMAGE_CAPTURE_FORMAT
import com.bomzzn.cameralib.databinding.CameraUiContainerBinding
import com.bomzzn.cameralib.databinding.FragmentCameraBinding
import com.bomzzn.cameralib.utils.createImageFile
import com.bomzzn.cameralib.utils.hasBackCamera
import com.bomzzn.cameralib.utils.hasFrontCamera
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 */
class CameraFragment : Fragment() {

    private val TAG = CameraFragment::class.java.canonicalName

    private var binding: FragmentCameraBinding? = null
    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN

    private val forceImageCapture: Boolean by lazy {
        activity?.intent?.extras?.getBoolean(KEY_CAMERA_CAPTURE_FORCE) == true
    }
    private val frontCameraEnable: Boolean by lazy {
        activity?.intent?.extras?.getBoolean(KEY_FRONT_CAMERA) == true
    }
    private val imgFileName: String by lazy {
        activity?.intent?.extras?.getString(KEY_FILENAME, "")!!
    }
    private val imageCaptureFormat: Int by lazy {
        activity?.intent?.extras?.getInt(KEY_IMAGE_CAPTURE_FORMAT, ImageFormat.JPEG)!!
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private val orientationEventListener by lazy {
        object : OrientationEventListener(
            requireContext()
        ) {
            override fun onOrientationChanged(orientation: Int) {
                println("AAA:: orientation: $orientation")
                if (orientation == ORIENTATION_UNKNOWN) {
                    enableCaptureBtn()
                    showSuccessToast()
                    readyBg()
                    return
                }
                deviceOrientation = orientation
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                val isCaptureReady = when (orientation) {
                    in 0..10 -> true
                    in 85..95 -> true
                    in 175..185 -> true
                    in 265..275 -> true
                    else -> false
                }
                if (!forceImageCapture) return
                if (isCaptureReady) {
                    enableCaptureBtn()
                    showSuccessToast()
                    readyBg()
                } else {
                    disableCaptureBtn()
                    showWarningToast()
                    warningBg()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OnBackPress callback
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requireActivity().finish()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(requireActivity(), callback)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCameraBinding.inflate(
            inflater,
            container,
            false
        )
        return binding!!.root
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(),
                com.bomzzn.cameralib.R.id.fragment_container
            ).navigate(
                CameraFragmentDirections.actionCameraFragmentToPermissionsFragment()
            )
            return
        }
    }

    override fun onDestroyView() {
        binding = null
        cameraUiContainerBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    @SuppressLint("MissingPermission", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        // Wait for the views to be properly laid out
        binding!!.viewFinder.post {
            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            setUpCamera()
        }

        // Listen to tap events on the viewfinder and set them as focus regions
        binding!!.viewFinder.setOnTouchListener { _: View, motionEvent: MotionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> return@setOnTouchListener true
                MotionEvent.ACTION_UP -> {
                    // Get the MeteringPointFactory from PreviewView
                    val factory = binding!!.viewFinder.meteringPointFactory

                    // Create a MeteringPoint from the tap coordinates
                    val point = factory.createPoint(motionEvent.x, motionEvent.y)

                    // Create a MeteringAction from the MeteringPoint, you can configure it to specify the metering mode
                    val action = FocusMeteringAction.Builder(point).build()

                    // Trigger the focus and metering. The method returns a ListenableFuture since the operation
                    // is asynchronous. You can use it get notified when the focus is successful or if it fails.
                    camera!!.cameraControl.startFocusAndMetering(action)

                    return@setOnTouchListener true
                }
                else -> return@setOnTouchListener false
            }
        }

    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        updateCameraUi()
    }
    // endregion

    // region Camera View code
    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            lensFacing = when {
                hasBackCamera(cameraProvider!!) -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera(cameraProvider!!) -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // Enable or disable switching between cameras
            updateCameraSwitchButton()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        val metrics = requireContext().resources.displayMetrics
        var screenAspectRatio: Int =
            metrics.heightPixels / metrics.widthPixels        // Get screen metrics used to setup camera for full screen resolution


        val rotation = binding!!.viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        try {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .setFlashMode(FLASH_MODE_AUTO)
                .setBufferFormat(imageCaptureFormat)
                .build()
        } catch (e: Exception) {
            Toast.makeText(
                requireActivity(),
                "Image format unsupported", Toast.LENGTH_LONG
            ).show()
            Log.d(TAG, "bindCameraUseCases: imagecaptureformat error: ${e.localizedMessage}")
        }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(binding!!.viewFinder.surfaceProvider)
            observeCameraState(camera?.cameraInfo!!)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            run {
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // Ask the user to close other camera apps
                        Log.d(TAG, "observeCameraState: CameraState: Pending Open")
                    }
                    CameraState.Type.OPENING -> {
                        // Show the Camera UI
                        Log.d(TAG, "observeCameraState: CameraState: Opening")
                    }
                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
                        Log.d(TAG, "observeCameraState: CameraState: Open")
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                        Log.d(TAG, "observeCameraState: CameraState: Closing")
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                        Log.d(TAG, "observeCameraState: CameraState: Closed")
                    }
                }
            }

            cameraState.error?.let { error ->
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(
                            context,
                            "Stream config error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(
                            context,
                            "Camera in use",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                        Toast.makeText(
                            context,
                            "Max cameras in use",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(
                            context,
                            "Other recoverable error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(
                            context,
                            "Camera disabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(
                            context,
                            "Fatal error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(
                            context,
                            "Do not disturb mode enabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {

        // Remove previous UI if any
        cameraUiContainerBinding?.root?.let {
            binding!!.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            binding!!.root,
            true
        )
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createImageFile(requireContext(), imgFileName)

                // Setup image capture metadata
                val metadata = ImageCapture.Metadata().apply {
                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                            val exif = Exif.createFromFile(photoFile)
                            val rotation = exif.rotation
                            Log.d("EXIF::Rotation", "onImageSaved: rotation:: $rotation")
                            println("AAA:: $rotation")

                            val intent = Intent()
                            intent.data = savedUri
                            requireActivity().setResult(Activity.RESULT_OK, intent)
                            requireActivity().finish()
                        }
                    })
            }
        }

        // Setup for button used to switch cameras
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }
        /*
        if (forceImageCapture) {
            cameraUiContainerBinding?.cameraCaptureButton?.visibility = View.VISIBLE
        } else {
            cameraUiContainerBinding?.cameraCaptureButton?.visibility = View.GONE
        }*/
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled =
                hasBackCamera(cameraProvider!!) || hasFrontCamera(cameraProvider!!)

            if (frontCameraEnable) {
                cameraUiContainerBinding?.cameraSwitchButton?.visibility = View.VISIBLE
            } else {
                cameraUiContainerBinding?.cameraSwitchButton?.visibility = View.GONE
            }
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }
    // endregion

    private fun showSuccessToast() {
        cameraUiContainerBinding?.warningView?.layoutWarning?.visibility = View.GONE
        cameraUiContainerBinding?.successView?.layoutSuccess?.visibility = View.VISIBLE
    }

    private fun showWarningToast() {
        cameraUiContainerBinding?.successView?.layoutSuccess?.visibility = View.GONE
        cameraUiContainerBinding?.warningView?.layoutWarning?.visibility = View.VISIBLE
    }

    private fun readyBg() {
        cameraUiContainerBinding?.cameraCaptureButton?.setBackgroundResource(com.bomzzn.cameralib.R.drawable.ic_shutter_ready)
    }

    private fun warningBg() {
        cameraUiContainerBinding?.cameraCaptureButton?.setBackgroundResource(com.bomzzn.cameralib.R.drawable.ic_shutter_warning)
    }

    private fun enableCaptureBtn() {
        cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
    }

    private fun disableCaptureBtn() {
        cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
    }
}