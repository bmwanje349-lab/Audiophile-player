import androidx.media3.common.audio.*
import androidx.media3.common.C
import com.example.audio.*
import java.nio.*
import kotlin.math.*
fun run(name:String, amp:Double, sr:Int=44100, smart:Boolean=true){
  val p=LoudnessProcessor(); p.cfg(AudioProcessor.AudioFormat(sr,2,C.ENCODING_PCM_FLOAT))
  LoudnessSettings.smartEnabled=smart
  val frames=sr*20; val bb=ByteBuffer.allocate(frames*8).order(ByteOrder.nativeOrder())
  val r=java.util.Random(3)
  for(i in 0 until frames){ val t=i.toDouble()/sr
    val env=0.5+0.5*sin(2*PI*1.5*t)
    val x=amp*(0.6*sin(2*PI*110*t)*env + 0.2*r.nextGaussian()*0.3 + (if(i%22050<20) 1.0 else 0.0)*0.8)
    bb.putFloat(x.toFloat()); bb.putFloat((x*0.9).toFloat()) }
  bb.flip()
  var peak=0f; var ms=0.0; var cnt=0; var inMs=0.0
  val inB=bb.duplicate().order(ByteOrder.nativeOrder())
  val o=p.push(bb); val tail=p.eos()
  val ob=ByteBuffer.allocate(o.limit()+tail.limit()).order(ByteOrder.nativeOrder()); ob.put(o); ob.put(tail); ob.flip()
  var i=0; while(ob.remaining()>=4){ val v=ob.getFloat(); peak=max(peak,abs(v)); if(i>sr*8*2){ms+=v*v;cnt++}; i++ }
  var j=0; while(inB.remaining()>=4){ val v=inB.getFloat(); if(j>sr*8*2){inMs+=v*v}; j++ }
  println("$name: outPeak=%.4f (%.2f dBFS) ceil=%.4f  rmsGain=%.2f dB  outFrames=%d".format(peak,20*log10(peak.toDouble()),10.0.pow(LoudnessSettings.ceilingDb/20.0),10*log10(ms/inMs), ob.limit()/8))
}
fun main(){
  run("quiet(-24dB amp)",0.063); run("mid(-12dB)",0.25); run("hot(0dB)",1.0); run("clipping-hot x3",3.0); run("quiet nosmart",0.063,smart=false)
  LoudnessSettings.preampDb=12f; run("preamp+12 hot",1.0)
}
