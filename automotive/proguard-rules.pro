# ── kotlinx.serialization ────────────────────────────────────────────────
# Without these, R8 strips/renames the generated $serializer classes and the
# Companion.serializer() accessors, so every @Serializable DTO fails to
# deserialize at runtime (the app compiles fine but crashes on the first API
# response). These are the rules published by the kotlinx.serialization project.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion *;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces: keep the API DTOs and their generated serializers outright.
-keep,includedescriptorclasses class uk.co.fuelprices.data.api.**$$serializer { *; }
-keepclassmembers class uk.co.fuelprices.data.api.** { *; }

# ── Retrofit / OkHttp ────────────────────────────────────────────────────
# Modern Retrofit/OkHttp ship their own consumer rules, but keep the API
# interface's generic signatures defensively (needed for return-type parsing).
-keepattributes Signature,Exceptions
-keep,allowobfuscation interface uk.co.fuelprices.data.api.FuelPricesApi
-keepclassmembers,allowobfuscation interface uk.co.fuelprices.data.api.FuelPricesApi { *; }

# ── Car App Library ──────────────────────────────────────────────────────
# The <service> and Screen classes are referenced from the merged manifest;
# keep them so R8 doesn't discard the car entry points.
-keep class uk.co.fuelprices.car.** { *; }
