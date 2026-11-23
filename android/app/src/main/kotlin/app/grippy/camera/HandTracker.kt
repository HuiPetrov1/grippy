// SPDX-License-Identifier: MPL-2.0
// Grippy: finding hands in a camera frame, on the phone (MediaPipe, Apache-2.0). Not part of upstream.

package app.grippy.camera

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** Wraps the hand model. One per open camera; [close] it when the camera closes. Not thread-safe. */
class HandTracker(context: Context) : AutoCloseable {
    private val landmarker: HandLandmarker
    private var lastStamp = 0L

    init {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = HandLandmarker.createFromOptions(context.applicationContext, options)
    }

    /**
     * The hands in [frame] once it is turned upright by [rotationDegrees]: each as 21 points,
     * x and y in 0…1 of the upright picture.
     */
    fun find(frame: Bitmap, rotationDegrees: Int, stampMs: Long): List<SeenHand> {
        // The model insists that time moves forward.
        val stamp = if (stampMs <= lastStamp) lastStamp + 1 else stampMs
        lastStamp = stamp
        val image = BitmapImageBuilder(frame).build()
        val processing = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()
        val result = try {
            landmarker.detectForVideo(image, processing, stamp)
        } finally {
            image.close()
        }
        return result.landmarks().mapNotNull { hand ->
            if (hand.size < 21) return@mapNotNull null
            SeenHand(FloatArray(42) { i -> if (i % 2 == 0) hand[i / 2].x() else hand[i / 2].y() })
        }
    }

    override fun close() {
        runCatching { landmarker.close() }
    }

    companion object {
        const val MODEL = "hand_landmarker.task"

        /** The lines of a drawn hand: pairs of point numbers. */
        val BONES: IntArray = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 4,
            0, 5, 5, 6, 6, 7, 7, 8,
            5, 9, 9, 10, 10, 11, 11, 12,
            9, 13, 13, 14, 14, 15, 15, 16,
            13, 17, 17, 18, 18, 19, 19, 20,
            0, 17,
        )
    }
}
