package android.content
import java.io.File
open class Context { val filesDir: File = File("."); open val applicationContext: Context get() = this }
