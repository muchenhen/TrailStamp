# Safety and recovery model

## Preview boundary

`preview` 读取 Android JSON、NEF 元数据和一个显式相册目录。它不会写入 RAW 或网站，但会在用户指定的报告目录生成 HTML 与 JSON，并可能把内嵌 ExifTool 解压到版本化本机缓存。

计划固定轨迹、每个 RAW 和每个目标网站 JSON 的 SHA-256。RAW 时间只来自 `SubSecDateTimeOriginal`、`DateTimeOriginal` 或 `CreateDate`，绝不静默使用文件修改时间。

## Apply boundary

执行前先验证计划中的绝对路径边界、简单输出文件名和目标唯一性，再完成全部哈希预检；预检失败时不会产生 RAW 或网站输出。输出目录在创建后再次解析真实路径，必须与输入目录分离，也不能通过 `..`、符号链接或 junction 落回输入目录。每个成功 RAW 副本都经过 ExifTool 回读，验证经纬度、海拔、水平误差、带毫秒的 GPS UTC 时间以及有值的 XMP 行政区；随后再次核对原始 RAW 哈希。

网站事务会把照片 JSON 和 `data/china.json` 快照并 fsync 到报告目录的 `backups/<UTC>/site/`。既有位置冲突默认整项跳过；发生 JSON 写入或网站命令失败时恢复快照。备份不会自动删除。

## Recovery

- RAW 输出在网站事务失败时可保留，因为它们已独立回读验证；`apply-log.json` 会把网站事务标为失败。
- 单张 RAW 写入或验证失败时，只删除该次尚未验证的明确输出文件；已验证成功的其他副本保留。
- 网站仓库不自动提交，因此成功后仍可用普通 `git diff` 审查。不要用强制 reset 代替报告备份。
- 原片目录应继续由用户自己的备份策略保护。GPSLog 的“原片不写入”不是外部磁盘故障的备份方案。
