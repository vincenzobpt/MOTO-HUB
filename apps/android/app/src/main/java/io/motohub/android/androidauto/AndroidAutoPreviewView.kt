package io.motohub.android.androidauto

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.motohub.android.aa.AaInput

class AndroidAutoPreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        keepScreenOn = true
        holder.addCallback(this)
        isClickable = true
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AndroidAutoPreviewRuntime.attach(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AndroidAutoPreviewRuntime.detach(holder.surface)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                AndroidAutoPreviewRuntime.sendTouch(
                    AaInput.ACTION_DOWN,
                    event.getPointerId(index),
                    event.getX(index).toInt(),
                    event.getY(index).toInt()
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    AndroidAutoPreviewRuntime.sendTouch(
                        AaInput.ACTION_MOVE,
                        event.getPointerId(index),
                        event.getX(index).toInt(),
                        event.getY(index).toInt()
                    )
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> {
                val index = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    event.actionIndex
                } else {
                    event.pointerCount - 1
                }
                if (index >= 0) {
                    AndroidAutoPreviewRuntime.sendTouch(
                        AaInput.ACTION_UP,
                        event.getPointerId(index),
                        event.getX(index).toInt(),
                        event.getY(index).toInt()
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (index in 0 until event.pointerCount) {
                    AndroidAutoPreviewRuntime.sendTouch(
                        AaInput.ACTION_UP,
                        event.getPointerId(index),
                        event.getX(index).toInt(),
                        event.getY(index).toInt()
                    )
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
