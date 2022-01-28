package com.bomzzn.cameralib

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.ImageFormat
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment

/**
 * Create CameraImagePicker object
 * */
class CameraXImagePicker {

    companion object {
        const val KEY_FRONT_CAMERA = "frontCamera"
        const val KEY_CAMERA_CAPTURE_FORCE = "forceCameraCapture"
        const val KEY_FILENAME = "IMG_FILENAME"
        const val KEY_IMAGE_CAPTURE_FORMAT = "imageCaptureFormat"
        const val KEY_SCREEN_ORIENTATION = "screenOrientation"

        /**
         * Use this to use CameraXImagePicker in Activity Class
         *
         * @param activity AppCompatActivity Instance
         */
        @JvmStatic
        fun with(activity: Activity): Builder {
            return Builder(activity)
        }

        /**
         * Calling from fragment
         * */
        @JvmStatic
        fun with(fragment: Fragment): Builder {
            return Builder(fragment)
        }
    }

    class Builder(private val activity: Activity) {

        private var fragment: Fragment? = null

        constructor(fragment: Fragment) : this(fragment.requireActivity()) {
            this.fragment = fragment
        }

        fun start(
            launcher: ActivityResultLauncher<Intent>,
            forceImageCapture: Boolean = true,
            enabledFrontCamera: Boolean = true,
            fileName: String = "",
            imageCaptureFormat: Int = ImageFormat.JPEG,
            screenOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        ) {
            startActivity(
                launcher,
                forceImageCapture,
                enabledFrontCamera,
                fileName,
                imageCaptureFormat,
                screenOrientation
            )
        }

        private fun startActivity(
            launcher: ActivityResultLauncher<Intent>,
            forceImageCapture: Boolean,
            enabledFrontCamera: Boolean,
            fileName: String,
            imageCaptureFormat: Int,
            screenOrientation: Int
        ) {
            val imagePickerIntent: Intent = if (fragment != null) {
                Intent(fragment?.requireActivity(), CameraXActivity::class.java)
            } else {
                Intent(activity, CameraXActivity::class.java)
            }
            imagePickerIntent.putExtra(KEY_CAMERA_CAPTURE_FORCE, forceImageCapture)
            imagePickerIntent.putExtra(KEY_FRONT_CAMERA, enabledFrontCamera)
            imagePickerIntent.putExtra(KEY_FILENAME, fileName)
            imagePickerIntent.putExtra(KEY_IMAGE_CAPTURE_FORMAT, imageCaptureFormat)
            imagePickerIntent.putExtra(KEY_SCREEN_ORIENTATION, screenOrientation)
            launcher.launch(imagePickerIntent)
        }
    }
}