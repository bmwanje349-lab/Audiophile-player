package androidx.media3.common.audio
import java.nio.ByteBuffer
import java.nio.ByteOrder
interface AudioProcessor {
  class AudioFormat(val sampleRate:Int, val channelCount:Int, val encoding:Int) { companion object { val NOT_SET = AudioFormat(-1,-1,-1) } }
  class UnhandledAudioFormatException(f: AudioFormat): Exception()
}
abstract class BaseAudioProcessor {
  protected abstract fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat
  protected open fun onFlush() {}
  protected abstract fun queueInput(inputBuffer: ByteBuffer)
  protected open fun onQueueEndOfStream() {}
  protected fun replaceOutputBuffer(n:Int): ByteBuffer { out = ByteBuffer.allocate(n).order(ByteOrder.nativeOrder()); return out }
  var out: ByteBuffer = ByteBuffer.allocate(0)
  fun cfg(f: AudioProcessor.AudioFormat) { onConfigure(f); onFlush() }
  fun push(b: ByteBuffer): ByteBuffer { queueInput(b); return out }
  fun eos(): ByteBuffer { onQueueEndOfStream(); return out }
}