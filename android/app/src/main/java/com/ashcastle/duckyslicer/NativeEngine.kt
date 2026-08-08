package com.ashcastle.duckyslicer

internal object NativeEngine {
    init {
        System.loadLibrary("duckyslicer")
    }

    external fun version(): String

    external fun inspectStl(path: String): String

    external fun previewGcode(path: String, layer: Int): String
}
