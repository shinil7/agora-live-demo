package com.shinil.agoralivedemo.data

import android.view.SurfaceView
import com.shinil.agoralivedemo.domain.VideoSurface

class AndroidVideoSurface(private val surfaceView: SurfaceView) : VideoSurface {
    override fun getSurface(): Any = surfaceView
    fun getSurfaceView(): SurfaceView = surfaceView
}