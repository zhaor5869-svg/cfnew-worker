# LaoDa Proxy — ProGuard / R8 规则
# 2026-11-17 补上：app/build.gradle.kts 的 release 块引用了这个文件，
# 之前文件不存在，release 构建会失败。现在给一个最小可用版。
# Debug 构建不走混淆，不受影响。

# Room：保留实体和 DAO 的注解处理结果
-keep class com.laoda.proxy.data.** { *; }
-keepclassmembers class com.laoda.proxy.data.** { *; }

# VpnService：不要混淆服务类名，系统要按 AndroidManifest 里的名字找它
-keep class com.laoda.proxy.service.ProxyVpnService { *; }
