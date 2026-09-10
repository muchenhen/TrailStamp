# Track export contract

主格式为 UTF-8 `gpslog.track/v1` JSON。所有时间是带 `Z` 的 RFC 3339 UTC；`timeZone` 只保存会话开始时的本地时区，不改变点时间的 UTC 语义。

```json
{
  "schema": "gpslog.track/v1",
  "session": {
    "id": "uuid",
    "startedAt": "2026-08-08T02:00:00Z",
    "endedAt": "2026-08-08T10:00:00Z",
    "timeZone": "Asia/Shanghai",
    "profile": "endurance",
    "targetIntervalSeconds": 240,
    "status": "completed",
    "gapCount": 0
  },
  "points": []
}
```

每个点包含 UTC 时间、`elapsedRealtimeNanos`、WGS-84 经纬度，以及可空的海拔、水平/垂直精度、速度、方位、provider、mock 与 `usable` 标志。行政区存在时使用 `admin.country/province/city/district/name`。

Android 会保存诊断用原始点，但以下点的 `usable` 为 false：坐标越界、早于会话开始、mock、没有精度或精度差于 500 米。Rust 会重新执行同等安全过滤，不能通过手工把 `usable` 改为 true 来绕过。

GPX 只包含可用点，并在扩展中保留水平精度和 elapsed realtime。CSV 包含所有诊断点和行政区字段，适合人工审查。

`session.profile` 当前有两个稳定值：`endurance` 表示平衡精度的耐力模式，`precision_walk` 表示 10 秒高精度的精细步行模式。`targetIntervalSeconds` 始终来自会话开始时的快照；读取方应以数值字段判断匹配间隔，不应根据当前应用设置或自行猜测 profile 参数。未知 profile 必须保留原值并使用导出的数值字段，不能静默改写轨迹。
