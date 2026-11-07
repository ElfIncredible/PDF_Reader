# PdfBox-Android: optional JPEG2000 decoder and crypto providers are not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# BouncyCastle (certificate signatures): keep only the provider and the algorithm classes it
# loads by name. Everything else is reached directly, so R8 can drop the many unused parts.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
