package com.example.musicplayer

import com.example.peq.PeqEngine
import com.example.peq.WidenerEngine

object DspRuntime {
    @Volatile var peqEngine: PeqEngine? = null
    @Volatile var widenerEngine: WidenerEngine? = null
}
