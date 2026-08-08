package com.ashcastle.duckyslicer

internal object NativeEngine {
    init {
        System.loadLibrary("duckyslicer")
    }

    external fun version(): String

    external fun inspectStl(path: String): String

    external fun previewGcodeRange(path: String, startLayer: Int, endLayer: Int): String
}
