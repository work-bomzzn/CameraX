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
        const val KEY_FILENAME = "IMG_FILENAME"
        const val KEY_IMAGE_CAPTURE_FORMAT = "imageCaptureFormat"
        const val KEY_EXPOSER = "exposerValue"

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
            fileName: String = "",
            imageCaptureFormat: Int = ImageFormat.JPEG,
            exposerValue: Int = 0,
        ) {
            startActivity(
                launcher,
                fileName,
                imageCaptureFormat,
                exposerValue
            )
        }

        private fun startActivity(
            launcher: ActivityResultLauncher<Intent>,
            fileName: String,
            imageCaptureFormat: Int,
            exposerValue: Int,
        ) {
            val imagePickerIntent: Intent = if (fragment != null) {
                Intent(fragment?.requireActivity(), CameraXActivity::class.java)
            } else {
                Intent(activity, CameraXActivity::class.java)
            }
            imagePickerIntent.putExtra(KEY_FILENAME, fileName)
            imagePickerIntent.putExtra(KEY_IMAGE_CAPTURE_FORMAT, imageCaptureFormat)
            imagePickerIntent.putExtra(KEY_EXPOSER, exposerValue)
            launcher.launch(imagePickerIntent)
        }
    }
}