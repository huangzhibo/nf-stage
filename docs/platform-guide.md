# nf-stage 平台接入参考

## 1. 目的

本文档供云平台研发参考，说明 nf-stage 插件提供的能力、配置接口和归档产物格式，便于平台侧对接。

## 2. 职责边界

### 2.1 nf-stage 负责

- 拦截 named workflow 执行，计算兼容性摘要
- 自动判断是否存在可复用的历史阶段资产
- 命中时从归档恢复，未命中时正常执行并归档产出
- 写入 `stage.json` 和归档文件
- 输出 `cached-stages.tsv` 记录本次运行的复用情况

### 2.2 nf-stage 不负责

- 集群调度、项目管理、用户交互
- 阶段资产的跨项目索引或全局检索
- 手动指定阶段恢复（当前未实现）

## 3. 配置接口

nf-stage 通过 Nextflow config scope `stage { }` 接收配置，平台侧通过 `-c` 传入：

```bash
nextflow run main.nf -c platform.config
```

```groovy
// platform.config
stage {
    archiveRoot      = 's3://bucket/nf-stage-archive'  // 归档根目录，默认 .nf-stage-archive
    cachedStagesFile = 'cached-stages.tsv'             // 复用记录文件，默认 cached-stages.tsv
}
```

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `archiveRoot` | String | `.nf-stage-archive` | 归档根目录，相对于 Nextflow 启动目录解析 |
| `cachedStagesFile` | String | `cached-stages.tsv` | 记录本次运行中被复用的阶段信息 |

## 4. 归档路径结构

```text
<archiveRoot>/<stage>/<digest-prefix-16>/
├── stage.json
├── 0/          # 第 1 个 emission
│   ├── sample1.bam
│   └── sample1.bam.bai
├── 1/          # 第 2 个 emission
│   └── sample2.bam
└── ...
```

- `<stage>` 为 named workflow 名称（如 `ALIGN`、`CALL`）
- `<digest-prefix-16>` 为 `compatibility_digest` 的前 16 位十六进制字符
- 阶段资产只读，相同 digest 不会重复写入

## 5. stage.json Schema

每个归档目录下包含一个 `stage.json`：

```json
{
  "schema_version": "v1",
  "stage": "ALIGN",
  "compatibility_digest": "sha256:db6fe4bca85f1cf2...",
  "created_at": "2026-04-10T14:06:13.530127+08:00",
  "integrity": {
    "status": "ok"
  },
  "channels": {
    "bam_ch": {
      "type": "queue",
      "items": [
        [
          { "type": "value", "data": { "id": "SAMPLE1" } },
          { "type": "file", "name": "sample1.bam", "checksum": "sha256:...", "size": 123456 },
          { "type": "file", "name": "sample1.bam.bai", "checksum": "sha256:...", "size": 1234 }
        ]
      ]
    }
  },
  "task_hashes": ["a6/18ddf1", "1c/fb5040"]
}
```

### 5.1 字段说明

| 字段 | 说明 |
|------|------|
| `schema_version` | 固定 `"v1"` |
| `stage` | named workflow 名称 |
| `compatibility_digest` | 完整 SHA256 摘要，用于复用判断 |
| `created_at` | 归档时间，ISO 8601 格式 |
| `integrity.status` | `"ok"` 表示归档完整，非 ok 不会被复用 |
| `channels` | 各输出 channel 的序列化数据 |
| `task_hashes` | 该阶段涉及的 Nextflow task hash 列表（流程完成后回填） |

### 5.2 channels 结构

`channels` 中每个 key 对应阶段 `emit:` 的一个输出 channel：

- `type`：`"value"` 或 `"queue"`，对应 Nextflow 的 value channel / queue channel
- `items`：emission 列表，每个 emission 是一个 element 数组

element 有两种类型：

- **file**：`{ "type": "file", "name": "...", "checksum": "sha256:...", "size": 12345 }`
- **value**：`{ "type": "value", "data": <any> }`

## 6. 兼容性摘要计算

`compatibility_digest` 基于以下内容的 SHA256：

1. **stage 名称** — named workflow 名称
2. **静态参数** — 传入该 workflow 的非 channel 参数（如 reference path、params 值等）
3. **channel 输入内容** — 所有 channel 参数的实际数据（包含文件内容 hash）

任一项变化都会产生不同的 digest，触发重新执行。上游阶段的变化会通过 channel 数据内容自然传递到下游 digest。

## 7. 自动恢复流程

1. 每个 named workflow 被调用时，插件拦截执行
2. 克隆 channel 参数，收集实际输入数据
3. 计算 `compatibility_digest`
4. 在 `archiveRoot/<stage>/<digest-prefix-16>/stage.json` 查找归档
5. 若找到且 `integrity.status == "ok"`：从归档恢复输出，跳过执行
6. 若未找到：正常执行，完成后写入归档

## 8. cached-stages.tsv

每次运行后生成，记录本次从归档恢复的阶段：

```tsv
stage	digest	task_count	archive_path	archived_at
ALIGN	sha256:db6fe4b...	4	.nf-stage-archive/ALIGN/db6fe4bca85f1cf2	2026-04-10T14:06:13+08:00
```

平台可读取此文件获知哪些阶段被复用、对应的归档路径和原始归档时间。

## 9. 阶段定义要求

nf-stage 以 named workflow 作为阶段边界。流程侧需满足：

- 用 named workflow 定义阶段，声明 `take:` 和 `emit:`
- `emit:` 的输出即为归档内容
- workflow 输出不依赖阶段内部临时文件

详见 [流程开发规范](pipeline-guide.md)。
