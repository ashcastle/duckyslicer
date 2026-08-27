package com.ashcastle.duckyslicer

import androidx.core.content.FileProvider

/** Read-only URI bridge limited to bounded, app-generated profile bundles. */
class ProfileBundleShareProvider : FileProvider(R.xml.profile_bundle_share_paths)
