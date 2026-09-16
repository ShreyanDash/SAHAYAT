# Firebase / Firestore models are mapped manually (no reflection), so no keep rules are needed for them.
-keepattributes Signature
-keepattributes *Annotation*
# ML Kit barcode scanning
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
