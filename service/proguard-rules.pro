# SPDX-License-Identifier: GPL-3.0-or-later
-keep class app.nectarlink.services.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
