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
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> AaInput.ACTION_DOWN
            MotionEvent.ACTION_MOVE -> AaInput.ACTION_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> AaInput.ACTION_UP
            else -> return true
        }
        AndroidAutoPreviewRuntime.sendTouch(action, event.x.toInt(), event.y.toInt())
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
