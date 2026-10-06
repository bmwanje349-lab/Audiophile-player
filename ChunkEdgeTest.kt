package test
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import kotlin.math.abs

private class Scale(private val s: Float): VocalSeparatorCore.ModelRunner {
    override fun run(x: FloatArray)=FloatArray(x.size){x[it]*s}
}
private fun maxErr(a:FloatArray,b:FloatArray):Float { var m=0f; for(i in a.indices)m=maxOf(m,abs(a[i]-b[i]));return m }
fun main(){
    val lengths=intArrayOf(1,100,10_000,VocalSeparatorCore.MODEL_SAMPLES,VocalSeparatorCore.MODEL_SAMPLES+1,500_000,VocalSeparatorCore.STRIDE_SAMPLES+1,1_100_000)
    for(n in lengths){
        val l=FloatArray(n){i->((i%97)-48)/100f}
        val r=FloatArray(n){i->((i%89)-44)/120f}
        val inS=VocalSeparatorCore.Stereo(l,r)
        val o=VocalSeparatorCore.separateAtModelRate(inS,Scale(.37f))
        val e=maxOf(maxErr(o.left,FloatArray(n){l[it]*.37f}),maxErr(o.right,FloatArray(n){r[it]*.37f}))
        println("n=$n err=$e")
        check(e < 2e-5f)
    }
    println("CHUNK EDGE/TAIL OLA TEST PASSED")
}
