# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# androidx.test:runner is on androidTestImplementation, so its consumer
# proguard rules are not applied to the app's R8 step. Its transitive deps
# (androidx.tracing, Kotlin stdlib) end up in the app's classpath but get
# stripped because the app itself does not reference them. The release
# androidTest APK then assumes those classes live in the app APK, so the
# runner's onCreate crashes with NoClassDefFoundError at runtime. Keep them
# in the app APK so instrumented tests can start.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }

# Espresso pulls in error_prone_annotations, whose @IncompatibleModifiers /
# @RequiredModifiers reference javax.lang.model.element.Modifier — a JDK compiler
# API that does not exist on Android. The annotations are compile-time only, so the
# reference is never followed at runtime. androidTest-only, hence suppressed here
# rather than in the SDK's consumer rules.
-dontwarn javax.lang.model.element.Modifier