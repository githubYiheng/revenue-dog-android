# RevenueDog SDK consumer ProGuard 规则（随 aar 分发，设计 §7）。
#
# 只保留公开面与反射/序列化会用到的部分；宿主**不要**把自己的代码放进 org.revdog.**
# 包名下 —— 下面的 keep 规则会让那些类在 release 里无法被裁剪（对照 RC codegen README 的提醒）。
-keep class org.revdog.purchases.** { public protected *; }
-keepclassmembers class org.revdog.purchases.** {
    public <init>(...);
}

# Play Billing 的回调接口通过 IPC 反射调用。
-keep class com.android.billingclient.api.** { *; }

# 保留 SDK 公开注解，宿主编译期要看得到。
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
