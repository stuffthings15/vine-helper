-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Remove verbose diagnostics and scraped item metadata from release APKs.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}