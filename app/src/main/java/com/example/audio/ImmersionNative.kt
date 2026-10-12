package com.example.audio

import android.content.res.AssetManager
import java.nio.ByteBuffer

object ImmersionNative {
    init { System.loadLibrary("audiophile_immersion") }

    @JvmStatic external fun loadAssets(assets: AssetManager): Boolean
    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(h: Long)
    @JvmStatic external fun prepare(h: Long, sampleRate: Int)
    @JvmStatic external fun reset(h: Long)
    @JvmStatic external fun setEnabled(h: Long, value: Boolean)
    @JvmStatic external fun setSpace(h: Long, value: Float)
    @JvmStatic external fun setAmbience(h: Long, value: Float)
    @JvmStatic external fun setAir(h: Long, value: Float)
    @JvmStatic external fun setBass(h: Long, value: Float)
    @JvmStatic external fun setProfile(h: Long, value: Float)
    @JvmStatic external fun process(h: Long, input: ByteBuffer, output: ByteBuffer, frames: Int)
}
