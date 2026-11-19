# FastBrowser release shrinking (v1.0.7). CI builds the debug APK only, so
# these rules apply when a release build is made by hand.
# Keep the serializable models — kotlinx-serialization reads their fields.
-keep class com.fastbrowser.gologin.model.** { *; }
-keepclassmembers class com.fastbrowser.gologin.model.** { *; }
# Tink uses reflection / service loading for its primitives and key templates.
-keep class com.google.crypto.tink.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# Keep the bridge and WebSocket transport entry points (referenced by name
# in diagnostics and unit tests).
-keep class com.fastbrowser.gologin.network.LocalProxyBridge { *; }
-keep class com.fastbrowser.gologin.network.WebSocketUpstreamSocket { *; }
