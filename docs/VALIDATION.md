# 公开候选版验证记录

日期：2026-09-09。验证主机：Apple Silicon macOS，Rust 1.95.0，JDK 17，
Android SDK Platform 35 / Build Tools 35.0.0，Gradle wrapper 8.10.2。

## 本次实际执行

| 检查 | 结果 |
| --- | --- |
| `cargo fmt --all -- --check` | 通过 |
| `cargo test --locked --workspace --all-targets` | 通过：17 个 Rust 单元测试、3 个 CLI 集成测试 |
| `cargo clippy --locked --workspace --all-targets -- -D warnings` | 通过 |
| `cargo build --locked --release --bin gpslog-geotag` | macOS 原生 release 构建通过 |
| `cargo check --locked --workspace --all-targets --target x86_64-pc-windows-msvc` | Windows 目标编译检查通过；未链接或运行 Windows EXE |
| `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | 通过：18 个 JVM 测试、Android Lint、debug APK 与设备测试 APK 构建 |
| `python3 -m unittest discover -s scripts -p 'test_public_check.py'` | 通过：3 个公开内容检查回归用例 |
| 合成轨迹 `inspect --at 2000-01-01T00:04:00Z` | 实跑通过，结果见 [示例输出](examples/inspect.json) |
| 暂存区公开内容检查、差异空白检查、Gitleaks | 通过；仅扫描源码候选内容，不扫描本机私人目录 |
| Gradle wrapper JAR / ExifTool Windows 归档 | 校验和与原始上游发行内容一致；Gradle 分发 ZIP 也固定 SHA-256 |

Android Lint 没有错误，仍有 17 个警告：15 个依赖更新提示、1 个精确闹钟权限提示、
1 个 `mipmap-anydpi-v26` 在 minSdk 29 下的冗余限定符提示。
闹钟调用已有 `canScheduleExactAlarms()` 判断、`SecurityException` 捕获与非精确闹钟回退，
未为消除提示而扩大应用权限或关闭 Lint 检查。
较新的 SDK 管理工具还产生 XML 版本兼容提示；不影响以上构建和测试通过。

## 本次修复与整理

- 公共样例改为与真实拍摄无关的日期、0°/0° 坐标和虚构地点，Android 导出测试同步使用该契约。
- 修正 Android JSON 导出：`JsonObjectBuilder.put()` 返回旧值，原来的 `let { put(...) } ?: put(..., JsonNull)`
  会把已写入的结束时间和 provider 覆盖成 null。现在先选择 JSON 值再插入，并覆盖非空和空值路径。
- 修正设备迁移配置中的冗余排除项；迁移仍只允许数据库与指定设置，凭据不在允许列表中。
- 补入 Room 数据库 v2 的生成 schema，供迁移测试读取；没有包含数据库记录。
- 添加跨平台只读 `inspect` 入口、公开内容检查、MIT 许可、架构/隐私/集成文档。

## 未执行的验证

- 没有在本次整理中连接手机、运行 `connectedDebugAndroidTest`，也没有将新 APK 安装到手机。
- 没有在 Windows 运行本次构建的完整 RAW / ExifTool / 网站回滚端到端流程。
- 没有用真实轨迹、原片或服务器凭据做测试，也没有向上传服务发送数据。
- 没有完成户外 8 小时续航、各厂商后台存活和精细步行路线密度验收。

历史私人测试日志未作为公开资料保留，也未把其中的环境、坐标或旧结果当作本次验证。
后续版本应按实际平台重跑相关检查，并更新本记录。
