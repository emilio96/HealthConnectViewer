# Health Connect record classes are resolved reflectively by KClass from
# ReadRecordsRequest, so their names and members must survive shrinking.
-keep class androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.aggregate.** { *; }

# Kotlin reflection metadata is what makes KClass<T> lookups work at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Coroutines internals referenced only from generated code.
-dontwarn kotlinx.coroutines.**
