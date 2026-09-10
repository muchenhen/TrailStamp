# 可选服务与网站集成

GPSLog 的 Android 记录/导出和 Rust `inspect` 均可独立使用。本页描述可选对接契约，
不要求访问维护者的私人服务器或私人网站仓库。

## 照片网站目录

RAW 的 `preview` / `doctor` 当前要求 `--site-repo`，相册必须位于其 `content/photos/` 下。
示例目录：

```text
site/
  content/photos/2000/demo/20000101-DEMO_0001.json
  data/china.json
  scripts/generate-map-data.js
  package.json
```

每张照片使用一个 JSON。以下内容为虚构示例：

```json
{
  "title": "Synthetic demo photo",
  "date": "2000-01-01T00:04:00",
  "location": {}
}
```

匹配依次尝试：RAW 文件名主干相同、JSON 文件名以 `-RAW主干` 结尾、拍摄时间在一秒内。
多个候选或没有候选均作为冲突，不静默猜测。时间优先读取 `exif.create_date`，其次 `date`。
成功写入时使用 `location.gps.latitude` / `longitude` 和可选行政区字段；既有不同位置默认不覆盖。

`doctor` 还会检查 Git 状态及 `package.json` 中的 `locations:backfill`、`test`、`typecheck`、
`build`、`maps:validate` 脚本。`apply --update-site` 运行位置回填、地图生成与地图校验；
这些是用户选定仓库的本地脚本，应先审查。失败时恢复本次网站变更，不自动提交或部署。

不想更新网站时，不传 `--update-site`。当前仍需要目录和可匹配 JSON；`inspect` 不需要这些。
`fixtures/site-rollback/` 的脚本**故意失败**，只适合一次性回滚测试，不要复制它作为正常网站配置。

## Android 轨迹上传

兼容后端需处理：

```http
PUT <configured-base>/api/processing/v1/tracks/<session-uuid>
Authorization: Bearer <user-supplied-token>
Content-Type: application/json; charset=utf-8
Content-Encoding: gzip
```

请求体为 gzip 压缩的 `gpslog.track/v1` JSON。客户端计算未压缩内容的 SHA-256，
记录上传 revision。服务端应校验 token、限制请求大小，并按用户与会话隔离存储。
不要仅凭 URL 中的 session ID 判定访问权限。

客户端使用 HTTPS、禁止 URL 内嵌用户名/密码、不跟随重定向，最多进行 8 次上传尝试。
状态码的成功/重试/永久失败分类以 `TrackUploadPolicy.kt` 为准。
此仓库没有实现服务端；不配置上传即可完整使用本地记录和导出。
