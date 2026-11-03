-keep class com.qrscan.app.** { *; }
-keep class * extends android.app.Activity { *; }
# XML 里以全限定名引用的自定义 View（R8 读不到布局文件）
-keep class * extends android.view.View { public <init>(android.content.Context, android.util.AttributeSet); }
-keep class * extends android.content.ContentProvider { *; }
-dontwarn **