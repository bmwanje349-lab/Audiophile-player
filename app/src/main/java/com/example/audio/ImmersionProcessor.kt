package com.example.audio
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
object ImmersionSettings{
 @Volatile var enabled=true;@Volatile var space=.35f;@Volatile var ambience=.30f;@Volatile var air=.30f;@Volatile var bass=.30f;@Volatile var profile=.50f
 private const val P="immersion_settings"
 fun load(c:Context){val p=c.getSharedPreferences(P,Context.MODE_PRIVATE);enabled=p.getBoolean("enabled",true);space=p.getFloat("space",.35f);ambience=p.getFloat("ambience",.30f);air=p.getFloat("air",.30f);bass=p.getFloat("bass",.30f);profile=p.getFloat("profile",.50f)}
 fun save(c:Context){c.getSharedPreferences(P,Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled).putFloat("space",space).putFloat("ambience",ambience).putFloat("air",air).putFloat("bass",bass).putFloat("profile",profile).apply()}
}
@UnstableApi class ImmersionProcessor:BaseAudioProcessor(){
 private var h=0L
 override fun onConfigure(f:AudioFormat):AudioFormat{if(f.encoding!=C.ENCODING_PCM_FLOAT||f.channelCount!=2)throw UnhandledAudioFormatException(f);if(h==0L)h=ImmersionNative.create();ImmersionNative.prepare(h,f.sampleRate);return f}
 override fun queueInput(input:ByteBuffer){val bytes=input.remaining();val frames=bytes/8;if(frames<=0){input.position(input.limit());return};val out=replaceOutputBuffer(bytes);ImmersionNative.setEnabled(h,ImmersionSettings.enabled);ImmersionNative.setSpace(h,ImmersionSettings.space);ImmersionNative.setAmbience(h,ImmersionSettings.ambience);ImmersionNative.setAir(h,ImmersionSettings.air);ImmersionNative.setBass(h,ImmersionSettings.bass);ImmersionNative.setProfile(h,ImmersionSettings.profile);ImmersionNative.process(h,input,out,frames);out.position(0);out.limit(bytes);input.position(input.limit())}
 override fun onFlush(){if(h!=0L)ImmersionNative.reset(h)}
 override fun onReset(){if(h!=0L){ImmersionNative.destroy(h);h=0L}}
}