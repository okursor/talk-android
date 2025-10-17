#
# Nextcloud Talk - Android Client
#
# SPDX-FileCopyrightText: 2017-2024 Nextcloud GmbH and Nextcloud contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
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

# R8 missing rules - suppress warnings for missing classes
-dontwarn com.google.common.base.Objects$ToStringHelper
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep WebRTC classes - critical for video/audio functionality
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep MediaTek specific classes
-dontwarn com.mediatek.cta.CtaUtils

# Keep reflection and JNI classes
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep data classes and their getters/setters
-keepclassmembers class * extends androidx.room.Entity {
    public <init>(...);
    public *** get*();
    public void set*(***);
}

# Keep ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Retrofit specific rules
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# A resource is loaded with a relative path so the package of this class must be preserved.
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# Gson specific classes. Gson uses generic type information stored in a class file when working with fields. Proguard
# removes such information by default, so configure it to keep all of it.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowshrinking,allowobfuscation class * extends com.google.gson.reflect.TypeToken {
    <init>();
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn com.google.gson.examples.android.model.**
-dontwarn sun.misc.**

# LoganSquare specific rules
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.bluelinelabs.logansquare.annotation.JsonField <fields>;
    @com.bluelinelabs.logansquare.annotation.JsonObject <fields>;
}

# Keep data classes for serialization/deserialization
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Dagger rules
-dontwarn dagger.internal.codegen.**
-keepclassmembers,allowobfuscation class * {
    @javax.inject.* *;
    @dagger.* *;
    <init>();
}
-keep class dagger.* { *; }
-keep class javax.inject.* { *; }
-keep class * extends dagger.internal.BindingGraphPlugin {
    public <init>();
}
-keep @dagger.Component class *
-keep @dagger.Module class *
-keep class *$$ModuleAdapter { *; }
-keep class *$$InjectAdapter { *; }
-keep class *$$StaticInjection { *; }

# EventBus rules
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Room rules
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-dontwarn org.jetbrains.annotations.**

# WorkManager rules
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# CameraX rules
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Compose rules
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# RxJava rules
-keep class rx.schedulers.Schedulers {
    public static <methods>;
}
-keep class rx.schedulers.ImmediateScheduler {
    public <init>(...);
}
-keep class rx.schedulers.TestScheduler {
    public <init>(...);
}
-keep class rx.schedulers.Schedulers {
    public static ** test();
}
-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
    long producerIndex;
    long consumerIndex;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}
-dontwarn rx.internal.util.unsafe.**

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Jackson rules
-keepattributes *Annotation*,EnclosingMethod,Signature
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-keep class org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-keep class com.fasterxml.jackson.annotation.** { *; }

# SQLCipher rules
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Media3/ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Markwon rules
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# Kotlin Serialization rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializersKt
-keep,includedescriptorclasses class * extends kotlinx.serialization.KSerializer { *; }
-keep,includedescriptorclasses class * extends kotlinx.serialization.descriptors.SerialDescriptor { *; }
-keep,includedescriptorclasses class * extends kotlinx.serialization.encoding.Decoder { *; }
-keep,includedescriptorclasses class * extends kotlinx.serialization.encoding.Encoder { *; }
-keepclassmembers class kotlinx.serialization.** {
    <methods>;
}
-keepclassmembers class * {
    @kotlinx.serialization.* *;
}

# Parceler rules
-keep interface org.parceler.Parcel
-keep @org.parceler.Parcel class * { *; }
-keep class **$$Parcelable { *; }

# FlexibleAdapter rules
-keep class eu.davidea.flexibleadapter.** { *; }
-dontwarn eu.davidea.flexibleadapter.**

# JodaTime rules
-dontwarn org.joda.time.**
-keep class org.joda.time.** { *; }

# Coil rules
-keep class coil.** { *; }
-dontwarn coil.**

# Material Dialogs rules
-keep class com.afollestad.materialdialogs.** { *; }
-dontwarn com.afollestad.materialdialogs.**

# MediaPipe rules
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# OpenCV rules
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Firebase rules (only for gplay flavor)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Google Play Services rules (only for gplay flavor)
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Android Common UI rules
-keep class com.nextcloud.android.common.ui.** { *; }
-dontwarn com.nextcloud.android.common.ui.**

# Keep all model classes and their fields
-keep class com.nextcloud.talk.models.** { *; }
-keep class com.nextcloud.talk.data.** { *; }

# Keep all API classes
-keep class com.nextcloud.talk.api.** { *; }

# Keep all UI classes that might be referenced by name
-keep class com.nextcloud.talk.ui.** { *; }

# Keep application class
-keep class com.nextcloud.talk.application.NextcloudTalkApplication { *; }

# Keep all activities, services, receivers, providers
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }

# Keep custom views
-keep class * extends android.view.View { *; }
-keep class * extends android.widget.* { *; }
-keep class * extends androidx.appcompat.widget.* { *; }
