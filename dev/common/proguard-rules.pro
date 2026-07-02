# ════════════════════════════════════════════════════════════════════
# AniKoto 180 — ProGuard / R8 rules
# ════════════════════════════════════════════════════════════════════

# ★ session 47 (v16.7): comprehensive rewrite to fix the "type reference
# constructed without actual type information" error that caused video
# playback to fail in release builds (R8 was stripping the generated
# $$serializer classes that kotlinx.serialization needs at runtime).

# ── 1. Keep ALL extension classes ────────────────────────────────────
# The Aniyomi app instantiates the source class reflectively, and the
# extension uses kotlinx.serialization heavily (DTOs for episode lists,
# server lists, video sources, metadata). Keeping everything in the
# anikoto package (including nested classes + subpackages like .metadata
# and .video) is the safest approach — prevents R8 from stripping or
# renaming serializers, companion objects, or @SerialName fields.
-keep class eu.kanade.tachiyomi.animeextension.en.anikoto.** { *; }

# ── 2. Keep generated kotlinx.serialization serializers ─────────────
# The compiler plugin generates $$serializer classes for each @Serializable
# type. R8 can strip these if not explicitly kept (the @Serializable annotation
# is on the DTO class, NOT on the generated serializer).
-keep class **$$serializer { *; }
-keepclassmembers class **$$serializer { *; }

# ── 3. Keep kotlinx.serialization attributes + companions ──────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

# Keep serializer companion objects + the serializer() method
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @SerialName annotated fields (used for case-sensitive JSON mapping)
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
