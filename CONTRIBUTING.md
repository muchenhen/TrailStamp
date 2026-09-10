# 开发与贡献

## 工具链

- Rust 1.95+；提交并使用 `Cargo.lock`，建议加 `--locked`。
- Android：JDK 17、Android SDK Platform 35、项目自带的 Gradle 8.10.2 wrapper。
- Windows RAW 集成测试需要自己的 NEF 和匹配时间范围的轨迹。
- Python 3.9+ 用于本地公开内容检查；Gitleaks 可用于更完整的历史扫描。

macOS 上可用 Android Studio 管理 JDK/SDK；命令行方式需配置 `JAVA_HOME` 和 `ANDROID_HOME`。
`local.properties` 只保存在本机，不要提交 SDK 绝对路径。使用 `./gradlew`，
无需在系统单独安装 Gradle。Windows 使用 `.\gradlew.bat`。

## 检查

```sh
cargo fmt --all -- --check
cargo test --locked --workspace --all-targets
cargo clippy --locked --workspace --all-targets -- -D warnings
./gradlew testDebugUnitTest lintDebug assembleDebug
```

修改轨迹格式或匹配规则时，同时检查 Android 导出器与 Rust 消费端。
`fixtures/sample-track.json` 是共享契约，修改它必须同步 `TrackExporterTest`。
不要用真实会话去更新 fixture。

修改 Windows 写入、输入哈希、目录边界或回滚逻辑时，除单元测试外，
还应在 Windows 用明确选择的输入副本进行端到端验证：

```powershell
.\scripts\run-nef-e2e.ps1 `
  -RawFile private\raw\DEMO_0001.NEF `
  -Track private\session.json `
  -SiteRepo private\site `
  -Album '2000\demo'
```

这里的文件名和相册名只是示例。不要将故意失败的 `fixtures/site-rollback` 当作生产网站。
需要验证网站事务时显式添加 `-UpdateSite`；预览和执行范围以传入参数为准。

## 提交前

```sh
git add <本次修改的文件>
python3 scripts/check-public.py
python3 -m unittest discover -s scripts -p 'test_public_check.py'
git diff --cached --check
git diff --cached
```

Windows 可用 `py -3 scripts/check-public.py`。脚本检查**暂存区**，不会上传任何文件，
也不会改动 Git 历史。真实轨迹、日志、报告、数据库、密钥及原片应放在被忽略的目录。
`.gitignore` 不会清除已经跟踪的文件，所以仍需检查暂存区和图片/附件。

准备发布时，在本机另外运行：

```sh
gitleaks git . --log-opts="--all --full-history" --redact=100
```

本仓库不配置 GitHub Actions；保持本地验证流程。贡献说明中列出实际执行过的检查，
没有设备或平台时如实说明，不要把单元测试通过写成真机/RAW 写入验收通过。

## 改动范围

优先解决可复现问题，并保留以下边界：原片只读、上传显式选择、输入变化整体拒绝、
网站变更可回滚。请在 PR 中写清问题、最终行为和验证结果；附带数据须为合成数据。
新依赖或素材需要记录来源与许可证。GPSLog 原创代码使用 MIT，贡献者应有权提交相应内容。
