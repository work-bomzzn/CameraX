package com.bomzzn.cameralib.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.bomzzn.cameralib.CameraXImagePicker.Companion.KEY_EXPOSER
import com.bomzzn.cameralib.CameraXImagePicker.Companion.KEY_FILENAME
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

    private val imgFileName: String by lazy {
        activity?.intent?.extras?.getString(KEY_FILENAME, "")!!
    }
    private val imageCaptureFormat: Int by lazy {
        activity?.intent?.extras?.getInt(KEY_IMAGE_CAPTURE_FORMAT, ImageFormat.JPEG)!!
    }
    private val exposer: Int by lazy {
        activity?.intent?.extras?.getInt(KEY_EXPOSER, 0)!!
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        cameraExecutor.shutdown()
    }

    @SuppressLint("MissingPermission", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding!!.viewFinder.post {
            updateCameraUi()
            setUpCamera()
        }

        binding!!.viewFinder.setOnTouchListener { _: View, motionEvent: MotionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> return@setOnTouchListener true
                MotionEvent.ACTION_UP -> {
                    val factory = binding!!.viewFinder.meteringPointFactory
                    val point = factory.createPoint(motionEvent.x, motionEvent.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera!!.cameraControl.startFocusAndMetering(action)
                    return@setOnTouchListener true
                }
                else -> return@setOnTouchListener false
            }
        }

    }
    // endregion

    // region Camera View code
    /** Initialize CameraX, and epare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            lensFacing = when {
                hasBackCamera(cameraProvider!!) -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera(cameraProvider!!) -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }
            updateCameraSwitchButton()
            bindCameraUseCases()
            camera!!.cameraControl.setExposureCompensationIndex(exposer)
                .addListener({
                    val currentExposureIndex =
                        camera!!.cameraInfo.exposureState.exposureCompensationIndex
                }, cameraExecutor)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        val metrics = requireContext().resources.displayMetrics
        var screenAspectRatio: Int =
            metrics.heightPixels / metrics.widthPixels
        val rotation = binding!!.viewFinder.display.rotation
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        try {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(screenAspectRatio)
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

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture
            )

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
        cameraUiContainerBinding?.root?.let {
            binding!!.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            binding!!.root,
            true
        )
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            imageCapture?.let { imageCapture ->
                val photoFile = createImageFile(requireContext(), imgFileName)
                val metadata = ImageCapture.Metadata().apply {
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            Log.d(TAG, "Photo capture succeeded: $savedUri")
                            val intent = Intent()
                            intent.data = savedUri
                            requireActivity().setResult(Activity.RESULT_OK, intent)
                            requireActivity().finish()
                        }
                    })
            }
        }

        cameraUiContainerBinding?.cameraSwitchButton?.let {
            it.isEnabled = false
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                bindCameraUseCases()
            }
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled =
                hasBackCamera(cameraProvider!!) || hasFrontCamera(cameraProvider!!)
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }
    // endregion
}