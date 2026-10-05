# 旧版实现参考

此目录保留 1.7 的 YukiHookAPI/DexKit 实现，用于对照旧相机的行为。
它不属于 Gradle 的 main 源集，不会编译或打包，也不是现代 API 的兼容入口。

2.0 的实际入口和独立 Camera2 拍摄服务位于 `src/main/java/`，注册使用 libxposed API 102。
