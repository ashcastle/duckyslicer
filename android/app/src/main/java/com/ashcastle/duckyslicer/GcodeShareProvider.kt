package com.ashcastle.duckyslicer

import androidx.core.content.FileProvider

/** Read-only URI bridge whose XML root is limited to retained G-code outputs. */
class GcodeShareProvider : FileProvider(R.xml.gcode_share_paths)
