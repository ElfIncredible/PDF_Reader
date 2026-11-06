# PdfBox-Android: optional JPEG2000 decoder and crypto providers are not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# BouncyCastle is used for certificate signatures; keep its providers and algorithms.
-keep class org.bouncycastle.** { *; }
-dontwarn javax.naming.**
