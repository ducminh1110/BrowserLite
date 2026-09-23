-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# WebChromeClient hidden file chooser callbacks used by KitKat WebView.
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
}
