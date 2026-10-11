package com.example.audio
import java.nio.ByteBuffer
object ImmersionNative{init{System.loadLibrary("audiophile_immersion")}
@JvmStatic external fun create():Long
@JvmStatic external fun destroy(h:Long)
@JvmStatic external fun prepare(h:Long,sampleRate:Int)
@JvmStatic external fun reset(h:Long)
@JvmStatic external fun setEnabled(h:Long,v:Boolean)
@JvmStatic external fun setSpace(h:Long,v:Float)
@JvmStatic external fun setAmbience(h:Long,v:Float)
@JvmStatic external fun setAir(h:Long,v:Float)
@JvmStatic external fun setBass(h:Long,v:Float)
@JvmStatic external fun setProfile(h:Long,v:Float)
@JvmStatic external fun process(h:Long,input:ByteBuffer,output:ByteBuffer,frames:Int)
}