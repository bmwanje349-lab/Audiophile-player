package android.content
class Context { companion object { const val MODE_PRIVATE=0 }; fun getSharedPreferences(n:String,m:Int):SharedPreferences = TODO() }
interface SharedPreferences { fun getBoolean(k:String,d:Boolean):Boolean; fun getFloat(k:String,d:Float):Float; fun edit():Editor
 interface Editor { fun putBoolean(k:String,v:Boolean):Editor; fun putFloat(k:String,v:Float):Editor; fun apply() } }