package com.example.musicplayer

import com.example.peq.PeqEngine

object DspRuntime {
    @Volatile var peqEngine: PeqEngine? = null
}
