-keep class com.example.helloworld.UserService {
    <init>();
    <init>(android.content.Context);
}
-keep class com.example.helloworld.IUserService { *; }
-keep class com.example.helloworld.IUserService$Stub { *; }
-keep class com.example.helloworld.IUserService$Default { *; }

-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**