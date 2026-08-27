from __future__ import annotations

import unittest

from tools.verify_artifact_manifest import VerificationError, verify_aapt_output


def valid_manifest(*, debug: bool = False) -> str:
    debug_application = (
        "      A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n"
        if debug
        else ""
    )
    debug_components = ""
    if debug:
        debug_components = """      E: activity (line=30)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.ProcessRecoveryHarnessActivity" (Raw: "com.ashcastle.duckyslicer.ProcessRecoveryHarnessActivity")
        A: android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
      E: activity (line=31)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.AccessibilityHarnessActivity" (Raw: "com.ashcastle.duckyslicer.AccessibilityHarnessActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=32)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.PreviewPerformanceHarnessActivity" (Raw: "com.ashcastle.duckyslicer.PreviewPerformanceHarnessActivity")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: activity (line=33)
        A: android:name(0x01010003)="androidx.compose.ui.tooling.PreviewActivity" (Raw: "androidx.compose.ui.tooling.PreviewActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
      E: activity (line=34)
        A: android:name(0x01010003)="androidx.activity.ComponentActivity" (Raw: "androidx.activity.ComponentActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
      E: provider (line=34)
        A: android:name(0x01010003)="androidx.core.content.FileProvider" (Raw: "androidx.core.content.FileProvider")
        A: android:exported(0x01010010)=(type 0x12)0x0
"""
    model_filters = """        E: intent-filter (line=70)
          E: action (line=71)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=72)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=73)
            A: android:mimeType(0x01010026)="text/x.gcode" (Raw: "text/x.gcode")
          E: data (line=74)
            A: android:mimeType(0x01010026)="application/x-gcode" (Raw: "application/x-gcode")
          E: data (line=75)
            A: android:mimeType(0x01010026)="application/gcode" (Raw: "application/gcode")
          E: data (line=76)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=77)
          E: action (line=78)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=79)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=80)
            A: android:mimeType(0x01010026)="application/octet-stream" (Raw: "application/octet-stream")
          E: data (line=81)
            A: android:mimeType(0x01010026)="text/plain" (Raw: "text/plain")
          E: data (line=82)
            A: android:host(0x01010028)="*" (Raw: "*")
          E: data (line=83)
            A: android:pathPattern(0x0101002c)=".*.gcode" (Raw: ".*.gcode")
          E: data (line=84)
            A: android:pathPattern(0x0101002c)=".*.GCODE" (Raw: ".*.GCODE")
          E: data (line=85)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=80)
          E: action (line=81)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=82)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=83)
            A: android:mimeType(0x01010026)="model/stl" (Raw: "model/stl")
          E: data (line=84)
            A: android:mimeType(0x01010026)="application/sla" (Raw: "application/sla")
          E: data (line=85)
            A: android:mimeType(0x01010026)="application/vnd.ms-pki.stl" (Raw: "application/vnd.ms-pki.stl")
          E: data (line=86)
            A: android:mimeType(0x01010026)="model/3mf" (Raw: "model/3mf")
          E: data (line=87)
            A: android:mimeType(0x01010026)="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" (Raw: "application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
          E: data (line=88)
            A: android:mimeType(0x01010026)="application/vnd.ms-3mfdocument" (Raw: "application/vnd.ms-3mfdocument")
          E: data (line=89)
            A: android:mimeType(0x01010026)="model/obj" (Raw: "model/obj")
          E: data (line=90)
            A: android:mimeType(0x01010026)="application/x-tgif" (Raw: "application/x-tgif")
          E: data (line=91)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=92)
          E: action (line=93)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=94)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=95)
            A: android:mimeType(0x01010026)="application/octet-stream" (Raw: "application/octet-stream")
          E: data (line=96)
            A: android:host(0x01010028)="*" (Raw: "*")
          E: data (line=97)
            A: android:pathPattern(0x0101002c)=".*.stl" (Raw: ".*.stl")
          E: data (line=98)
            A: android:pathPattern(0x0101002c)=".*.STL" (Raw: ".*.STL")
          E: data (line=99)
            A: android:pathPattern(0x0101002c)=".*.3mf" (Raw: ".*.3mf")
          E: data (line=100)
            A: android:pathPattern(0x0101002c)=".*.3MF" (Raw: ".*.3MF")
          E: data (line=101)
            A: android:pathPattern(0x0101002c)=".*.obj" (Raw: ".*.obj")
          E: data (line=102)
            A: android:pathPattern(0x0101002c)=".*.OBJ" (Raw: ".*.OBJ")
          E: data (line=103)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=104)
          E: action (line=105)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=106)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=107)
            A: android:mimeType(0x01010026)="application/zip" (Raw: "application/zip")
          E: data (line=108)
            A: android:mimeType(0x01010026)="application/x-zip-compressed" (Raw: "application/x-zip-compressed")
          E: data (line=109)
            A: android:host(0x01010028)="*" (Raw: "*")
          E: data (line=110)
            A: android:pathPattern(0x0101002c)=".*.3mf" (Raw: ".*.3mf")
          E: data (line=111)
            A: android:pathPattern(0x0101002c)=".*.3MF" (Raw: ".*.3MF")
          E: data (line=112)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=113)
          E: action (line=114)
            A: android:name(0x01010003)="android.intent.action.SEND" (Raw: "android.intent.action.SEND")
          E: category (line=115)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=116)
            A: android:mimeType(0x01010026)="application/vnd.duckyslicer.project+zip" (Raw: "application/vnd.duckyslicer.project+zip")
        E: intent-filter (line=117)
          E: action (line=118)
            A: android:name(0x01010003)="android.intent.action.SEND" (Raw: "android.intent.action.SEND")
          E: category (line=119)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=120)
            A: android:mimeType(0x01010026)="application/vnd.duckyslicer.profiles+json" (Raw: "application/vnd.duckyslicer.profiles+json")
        E: intent-filter (line=121)
          E: action (line=122)
            A: android:name(0x01010003)="android.intent.action.SEND" (Raw: "android.intent.action.SEND")
          E: category (line=123)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=124)
            A: android:mimeType(0x01010026)="text/x.gcode" (Raw: "text/x.gcode")
          E: data (line=117)
            A: android:mimeType(0x01010026)="application/x-gcode" (Raw: "application/x-gcode")
          E: data (line=118)
            A: android:mimeType(0x01010026)="application/gcode" (Raw: "application/gcode")
        E: intent-filter (line=119)
          E: action (line=120)
            A: android:name(0x01010003)="android.intent.action.SEND" (Raw: "android.intent.action.SEND")
          E: category (line=121)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=122)
            A: android:mimeType(0x01010026)="model/stl" (Raw: "model/stl")
          E: data (line=123)
            A: android:mimeType(0x01010026)="application/sla" (Raw: "application/sla")
          E: data (line=124)
            A: android:mimeType(0x01010026)="application/vnd.ms-pki.stl" (Raw: "application/vnd.ms-pki.stl")
          E: data (line=125)
            A: android:mimeType(0x01010026)="model/3mf" (Raw: "model/3mf")
          E: data (line=126)
            A: android:mimeType(0x01010026)="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" (Raw: "application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
          E: data (line=127)
            A: android:mimeType(0x01010026)="application/vnd.ms-3mfdocument" (Raw: "application/vnd.ms-3mfdocument")
          E: data (line=128)
            A: android:mimeType(0x01010026)="model/obj" (Raw: "model/obj")
          E: data (line=129)
            A: android:mimeType(0x01010026)="application/x-tgif" (Raw: "application/x-tgif")
        E: intent-filter (line=130)
          E: action (line=131)
            A: android:name(0x01010003)="android.intent.action.SEND_MULTIPLE" (Raw: "android.intent.action.SEND_MULTIPLE")
          E: category (line=132)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=133)
            A: android:mimeType(0x01010026)="model/stl" (Raw: "model/stl")
          E: data (line=134)
            A: android:mimeType(0x01010026)="application/sla" (Raw: "application/sla")
          E: data (line=135)
            A: android:mimeType(0x01010026)="application/vnd.ms-pki.stl" (Raw: "application/vnd.ms-pki.stl")
          E: data (line=136)
            A: android:mimeType(0x01010026)="model/3mf" (Raw: "model/3mf")
          E: data (line=137)
            A: android:mimeType(0x01010026)="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" (Raw: "application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
          E: data (line=138)
            A: android:mimeType(0x01010026)="application/vnd.ms-3mfdocument" (Raw: "application/vnd.ms-3mfdocument")
          E: data (line=139)
            A: android:mimeType(0x01010026)="model/obj" (Raw: "model/obj")
          E: data (line=140)
            A: android:mimeType(0x01010026)="application/x-tgif" (Raw: "application/x-tgif")
"""
    return f"""N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: android:versionCode(0x0101021b)=(type 0x10)0x1
    A: android:versionName(0x0101021c)="test" (Raw: "test")
    A: android:compileSdkVersion(0x01010572)=(type 0x10)0x24
    A: package="com.ashcastle.duckyslicer" (Raw: "com.ashcastle.duckyslicer")
    E: uses-sdk (line=7)
      A: android:minSdkVersion(0x0101020c)=(type 0x10)0x1a
      A: android:targetSdkVersion(0x01010270)=(type 0x10)0x24
    E: uses-permission (line=10)
      A: android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")
    E: uses-permission (line=11)
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE" (Raw: "android.permission.FOREGROUND_SERVICE")
    E: uses-permission (line=12)
      A: android:name(0x01010003)="android.permission.FOREGROUND_SERVICE_DATA_SYNC" (Raw: "android.permission.FOREGROUND_SERVICE_DATA_SYNC")
    E: uses-permission (line=13)
      A: android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")
    E: permission (line=14)
      A: android:name(0x01010003)="com.ashcastle.duckyslicer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "com.ashcastle.duckyslicer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      A: android:protectionLevel(0x01010009)=(type 0x11)0x2
    E: uses-permission (line=15)
      A: android:name(0x01010003)="com.ashcastle.duckyslicer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "com.ashcastle.duckyslicer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    E: application (line=20)
      A: android:name(0x01010003)="com.ashcastle.duckyslicer.DuckySlicerApplication" (Raw: "com.ashcastle.duckyslicer.DuckySlicerApplication")
{debug_application}      A: android:allowBackup(0x01010280)=(type 0x12)0x0
      A: android:extractNativeLibs(0x010104ea)=(type 0x12)0x0
      A: android:fullBackupContent(0x010104eb)=@0x7f0b0000
      A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff
      A: android:dataExtractionRules(0x0101063e)=@0x7f0b0001
      A: android:localeConfig(0x0101065b)=@0x7f0b0002
{debug_components}      E: provider (line=35)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.GcodeShareProvider" (Raw: "com.ashcastle.duckyslicer.GcodeShareProvider")
        A: android:authorities(0x01010018)="com.ashcastle.duckyslicer.slice-share" (Raw: "com.ashcastle.duckyslicer.slice-share")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:grantUriPermissions(0x0101001b)=(type 0x12)0xffffffff
        E: meta-data (line=36)
          A: android:name(0x01010003)="android.support.FILE_PROVIDER_PATHS" (Raw: "android.support.FILE_PROVIDER_PATHS")
          A: android:resource(0x01010025)=@0x7f0b0003
      E: provider (line=38)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.ProfileBundleShareProvider" (Raw: "com.ashcastle.duckyslicer.ProfileBundleShareProvider")
        A: android:authorities(0x01010018)="com.ashcastle.duckyslicer.profile-share" (Raw: "com.ashcastle.duckyslicer.profile-share")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:grantUriPermissions(0x0101001b)=(type 0x12)0xffffffff
        E: meta-data (line=39)
          A: android:name(0x01010003)="android.support.FILE_PROVIDER_PATHS" (Raw: "android.support.FILE_PROVIDER_PATHS")
          A: android:resource(0x01010025)=@0x7f0b0004
      E: service (line=40)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.SlicerProcessService" (Raw: "com.ashcastle.duckyslicer.SlicerProcessService")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:process(0x01010011)=":slicer" (Raw: ":slicer")
        A: android:foregroundServiceType(0x01010599)=(type 0x11)0x1
      E: activity (line=45)
        A: android:name(0x01010003)="com.ashcastle.duckyslicer.MainActivity" (Raw: "com.ashcastle.duckyslicer.MainActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:intentMatchingFlags=(type 0x11)0x2
        E: intent-filter (line=47)
          E: action (line=48)
            A: android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: category (line=49)
            A: android:name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
        E: intent-filter (line=51)
          E: action (line=52)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=53)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=54)
            A: android:mimeType(0x01010026)="application/vnd.duckyslicer.project+zip" (Raw: "application/vnd.duckyslicer.project+zip")
          E: data (line=55)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=57)
          E: action (line=58)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=59)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=60)
            A: android:mimeType(0x01010026)="application/zip" (Raw: "application/zip")
          E: data (line=61)
            A: android:mimeType(0x01010026)="application/x-zip-compressed" (Raw: "application/x-zip-compressed")
          E: data (line=62)
            A: android:mimeType(0x01010026)="application/octet-stream" (Raw: "application/octet-stream")
          E: data (line=63)
            A: android:host(0x01010028)="*" (Raw: "*")
          E: data (line=64)
            A: android:pathPattern(0x0101002c)=".*.duckyproject" (Raw: ".*.duckyproject")
          E: data (line=65)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=66)
          E: action (line=67)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=68)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=69)
            A: android:mimeType(0x01010026)="application/vnd.duckyslicer.profiles+json" (Raw: "application/vnd.duckyslicer.profiles+json")
          E: data (line=70)
            A: android:scheme(0x01010027)="content" (Raw: "content")
        E: intent-filter (line=71)
          E: action (line=72)
            A: android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")
          E: category (line=73)
            A: android:name(0x01010003)="android.intent.category.DEFAULT" (Raw: "android.intent.category.DEFAULT")
          E: data (line=74)
            A: android:mimeType(0x01010026)="application/json" (Raw: "application/json")
          E: data (line=75)
            A: android:mimeType(0x01010026)="application/octet-stream" (Raw: "application/octet-stream")
          E: data (line=76)
            A: android:host(0x01010028)="*" (Raw: "*")
          E: data (line=77)
            A: android:pathPattern(0x0101002c)=".*.duckyprofiles" (Raw: ".*.duckyprofiles")
          E: data (line=78)
            A: android:scheme(0x01010027)="content" (Raw: "content")
{model_filters}      E: provider (line=70)
        A: android:name(0x01010003)="androidx.startup.InitializationProvider" (Raw: "androidx.startup.InitializationProvider")
        A: android:exported(0x01010010)=(type 0x12)0x0
      E: receiver (line=75)
        A: android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
        A: android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
"""


class VerifyArtifactManifestTest(unittest.TestCase):
    def test_accepts_exact_release_and_debug_manifests(self) -> None:
        verify_aapt_output(valid_manifest(), "release")
        verify_aapt_output(valid_manifest(debug=True), "debug")

    def test_rejects_stale_target_sdk(self) -> None:
        source = valid_manifest().replace(
            "android:targetSdkVersion(0x01010270)=(type 0x10)0x24",
            "android:targetSdkVersion(0x01010270)=(type 0x10)0x23",
        )
        with self.assertRaisesRegex(VerificationError, "targetSdkVersion"):
            verify_aapt_output(source, "release")

    def test_rejects_added_permission(self) -> None:
        source = valid_manifest().replace(
            "    E: application (line=20)",
            "    E: uses-permission (line=19)\n"
            "      A: android:name(0x01010003)=\"android.permission.CAMERA\" "
            "(Raw: \"android.permission.CAMERA\")\n"
            "    E: application (line=20)",
        )
        with self.assertRaisesRegex(VerificationError, "permission allowlist"):
            verify_aapt_output(source, "release")

    def test_rejects_duplicate_permission(self) -> None:
        duplicate = (
            "    E: uses-permission (line=16)\n"
            "      A: android:name(0x01010003)=\"android.permission.INTERNET\" "
            "(Raw: \"android.permission.INTERNET\")\n"
        )
        source = valid_manifest().replace(
            "    E: application (line=20)",
            duplicate + "    E: application (line=20)",
        )
        with self.assertRaisesRegex(VerificationError, "permission allowlist"):
            verify_aapt_output(source, "release")

    def test_rejects_debuggable_release(self) -> None:
        source = valid_manifest().replace(
            "      A: android:allowBackup",
            "      A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n"
            "      A: android:allowBackup",
        )
        with self.assertRaisesRegex(VerificationError, "debuggable"):
            verify_aapt_output(source, "release")

    def test_rejects_new_exported_component(self) -> None:
        source = valid_manifest().replace(
            "      E: service (line=40)",
            "      E: receiver (line=39)\n"
            "        A: android:name(0x01010003)=\"example.UnprotectedReceiver\" "
            "(Raw: \"example.UnprotectedReceiver\")\n"
            "        A: android:exported(0x01010010)=(type 0x12)0xffffffff\n"
            "      E: service (line=40)",
        )
        with self.assertRaisesRegex(VerificationError, "component allowlist"):
            verify_aapt_output(source, "release")

    def test_rejects_exported_gcode_share_provider(self) -> None:
        source = valid_manifest().replace(
            'A: android:name(0x01010003)="com.ashcastle.duckyslicer.GcodeShareProvider" '
            '(Raw: "com.ashcastle.duckyslicer.GcodeShareProvider")\n'
            '        A: android:authorities(0x01010018)',
            'A: android:name(0x01010003)="com.ashcastle.duckyslicer.GcodeShareProvider" '
            '(Raw: "com.ashcastle.duckyslicer.GcodeShareProvider")\n'
            '        A: android:exported(0x01010010)=(type 0x12)0xffffffff\n'
            '        A: android:authorities(0x01010018)',
        ).replace(
            '        A: android:exported(0x01010010)=(type 0x12)0x0\n'
            '        A: android:grantUriPermissions(0x0101001b)',
            '        A: android:grantUriPermissions(0x0101001b)',
            1,
        )
        with self.assertRaisesRegex(VerificationError, "component allowlist"):
            verify_aapt_output(source, "release")

    def test_rejects_broad_gcode_share_provider_authority(self) -> None:
        source = valid_manifest().replace(
            "com.ashcastle.duckyslicer.slice-share",
            "com.ashcastle.duckyslicer.files",
        )
        with self.assertRaisesRegex(VerificationError, "share provider authority"):
            verify_aapt_output(source, "release")

    def test_rejects_broad_profile_bundle_share_provider_authority(self) -> None:
        source = valid_manifest().replace(
            "com.ashcastle.duckyslicer.profile-share",
            "com.ashcastle.duckyslicer.files",
        )
        with self.assertRaisesRegex(VerificationError, "profile bundle share provider authority"):
            verify_aapt_output(source, "release")

    def test_rejects_exported_profile_bundle_share_provider(self) -> None:
        source = valid_manifest().replace(
            'A: android:name(0x01010003)="com.ashcastle.duckyslicer.ProfileBundleShareProvider" '
            '(Raw: "com.ashcastle.duckyslicer.ProfileBundleShareProvider")\n'
            '        A: android:authorities(0x01010018)="com.ashcastle.duckyslicer.profile-share" '
            '(Raw: "com.ashcastle.duckyslicer.profile-share")\n'
            '        A: android:exported(0x01010010)=(type 0x12)0x0',
            'A: android:name(0x01010003)="com.ashcastle.duckyslicer.ProfileBundleShareProvider" '
            '(Raw: "com.ashcastle.duckyslicer.ProfileBundleShareProvider")\n'
            '        A: android:authorities(0x01010018)="com.ashcastle.duckyslicer.profile-share" '
            '(Raw: "com.ashcastle.duckyslicer.profile-share")\n'
            '        A: android:exported(0x01010010)=(type 0x12)0xffffffff',
        )
        with self.assertRaisesRegex(VerificationError, "component allowlist"):
            verify_aapt_output(source, "release")

    def test_rejects_exported_debug_performance_harness(self) -> None:
        source = valid_manifest(debug=True).replace(
            'A: android:name(0x01010003)="com.ashcastle.duckyslicer.PreviewPerformanceHarnessActivity" '
            '(Raw: "com.ashcastle.duckyslicer.PreviewPerformanceHarnessActivity")\n'
            '        A: android:exported(0x01010010)=(type 0x12)0x0',
            'A: android:name(0x01010003)="com.ashcastle.duckyslicer.PreviewPerformanceHarnessActivity" '
            '(Raw: "com.ashcastle.duckyslicer.PreviewPerformanceHarnessActivity")\n'
            '        A: android:exported(0x01010010)=(type 0x12)0xffffffff',
        )
        with self.assertRaisesRegex(VerificationError, "component allowlist"):
            verify_aapt_output(source, "debug")

    def test_rejects_file_scheme_import(self) -> None:
        source = valid_manifest().replace(
            'android:scheme(0x01010027)="content" (Raw: "content")',
            'android:scheme(0x01010027)="file" (Raw: "file")',
            1,
        )
        with self.assertRaisesRegex(VerificationError, "external intent"):
            verify_aapt_output(source, "release")

    def test_rejects_broad_profile_json_import(self) -> None:
        source = valid_manifest().replace(
            '          E: data (line=77)\n'
            '            A: android:pathPattern(0x0101002c)=".*.duckyprofiles" '
            '(Raw: ".*.duckyprofiles")\n',
            "",
        )
        with self.assertRaisesRegex(VerificationError, "external intent"):
            verify_aapt_output(source, "release")

    def test_rejects_broadened_model_import(self) -> None:
        source = valid_manifest().replace(
            '            A: android:mimeType(0x01010026)="model/stl" (Raw: "model/stl")\n',
            '            A: android:mimeType(0x01010026)="*/*" (Raw: "*/*")\n',
            1,
        )
        with self.assertRaisesRegex(VerificationError, "external intent"):
            verify_aapt_output(source, "release")

    def test_rejects_relaxed_incoming_intent_matching(self) -> None:
        source = valid_manifest().replace(
            "        A: android:intentMatchingFlags=(type 0x11)0x2\n",
            "",
        )
        with self.assertRaisesRegex(VerificationError, "intent-filter matching"):
            verify_aapt_output(source, "release")

    def test_rejects_enabled_backup(self) -> None:
        source = valid_manifest().replace(
            "android:allowBackup(0x01010280)=(type 0x12)0x0",
            "android:allowBackup(0x01010280)=(type 0x12)0xffffffff",
        )
        with self.assertRaisesRegex(VerificationError, "backup"):
            verify_aapt_output(source, "release")


if __name__ == "__main__":
    unittest.main()
