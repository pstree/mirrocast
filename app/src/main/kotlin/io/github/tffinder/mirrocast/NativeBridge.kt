package io.github.tffinder.mirrocast

object NativeBridge {
    init {
        System.loadLibrary("mirrocast_native")
    }

    external fun version(): String
}
