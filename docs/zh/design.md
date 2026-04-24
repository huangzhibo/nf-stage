# nf-stage 设计

[English](../design.md) | **中文**

## 目标

- 删除历史 `work/` 后，仍可复用已归档阶段继续后续分析
- 只长期保留真正需要跨阶段复用或最终交付的文件，尽量节省存储
- 用户修改参数后，自动判断哪些阶段可跳过、哪些必须重跑

## 核心术语

- **阶段**：一个 named workflow，有明确输入、稳定输出
- **阶段归档**：阶段成功执行后保存的不可变资产，含 `stage.json` + `emit:` 产物
- **阶段复用产物**：默认等同于阶段 `emit:`，供后续运行、workflow outputs、fan-in 依赖
- **兼容性摘要（`compatibility_digest`）**：判断历史归档能否直接复用的 SHA256，基于 stage 名称 + 静态参数 + channel 输入内容

任一输入变化 → 不同 digest → 触发重新执行。上游变化通过 channel 数据自然传递到下游 digest。阶段实现变更时，给 named workflow 增加显式入参 `stage_version` 可使旧归档失效（入参进入 digest）。

## 硬约束

1. 无匹配归档时，该阶段及其下游必须重跑
2. 命中相同 `compatibility_digest` 时，直接复用已有归档，不再写新归档
3. 目录型 `emit:` 若含超过 1000 个文件，运行时应报错（避免海量小文件拖垮归档）

## 复用判断流程

1. 每个 named workflow 被调用时，插件拦截执行
2. 克隆 channel 参数，收集实际输入数据
3. 计算 `compatibility_digest`
4. 在 `archiveRoot/<stage>/<digest-prefix-16>/stage.json` 查找归档
5. 找到且 `integrity.status == "ok"`：从归档发射输出，跳过执行
6. 未找到：正常执行，完成后写入归档

## stage.json Schema

```json
{
  "schema_version": "v1",
  "stage": "ALIGN",
  "compatibility_digest": "sha256:db6fe4bca85f1cf2...",
  "created_at": "2026-04-10T14:06:13.530127+08:00",
  "integrity": { "status": "ok" },
  "channels": {
    "bam_ch": {
      "type": "queue",
      "items": [
        [
          { "type": "value", "data": { "id": "SAMPLE1" } },
          { "type": "file",  "name": "sample1.bam", "checksum": "sha256:...", "size": 123456 }
        ]
      ]
    }
  },
  "task_hashes": ["a6/18ddf1", "1c/fb5040"]
}
```

| 字段 | 说明 |
|------|------|
| `schema_version` | 固定 `"v1"` |
| `stage` | named workflow 名称 |
| `compatibility_digest` | 完整 SHA256，用于复用判断 |
| `created_at` | 归档时间（ISO 8601）|
| `integrity.status` | `"ok"` 表示归档完整，其他值不会被复用 |
| `channels` | 各 `emit:` channel 的序列化数据 |
| `task_hashes` | 该阶段涉及的 Nextflow task hash 列表（流程完成后回填）|

`channels[name].items` 元素两种类型：

- `{ "type": "file", "name": "...", "checksum": "sha256:...", "size": N }`
- `{ "type": "value", "data": <any> }`

## 一写多读

多个运行指向同一 `archiveRoot` 时，当前实现仅支持"一写多读"：同一 digest 的归档由**唯一写者**产生，其他用户以读者身份复用。

- **写者**：`writable = true`（默认），命中复用、未命中执行并归档
- **读者**：`writable = false`，命中复用、未命中正常执行但不写归档、不回填 `task_hashes`

**启动探测**：`writable = true` 时，插件会向 `archiveRoot` 写一个 probe 文件验证写权限。失败则 warn 并自动降级为只读，避免流程跑到一半因归档写失败而出错。

**建议**：普通用户模板 `writable = false`，统一的 CI/预热运行 `writable = true`；在存储层 ACL 上对共享 `archiveRoot` 做写权限隔离作为兜底。

## 未实现提案

- [start-stage.md](start-stage.md) — `startStage` 从指定阶段开始执行（设计草案，待后续需要时实现）
