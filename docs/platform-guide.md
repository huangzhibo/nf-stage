# 百趣生信流程阶段归档与复用 — 平台接入规范

## 1. 目的

本文档定义平台如何接入阶段归档与复用能力，面向：

- 云平台研发
- 流程架构设计人员
- 阶段归档与复用运行时维护人员

## 2. 职责边界

### 2.1 平台负责

- 管理阶段资产位置（按 `project_sn`、集群、路径规则定位）
- 在手动模式下接收用户指定阶段资产
- 将恢复输入传给运行时
- 记录恢复行为与结果

平台不负责：

- 理解流程语义
- 推导阶段依赖
- 判断某阶段是否兼容可复用

这些判断统一由运行时完成。

### 2.2 流程负责

- 用 `named workflow` 定义阶段
- 通过 `emit:` 暴露阶段长期保留、可复用的输出
- 保证 `workflow outputs` 和 `fan-in` 不依赖阶段内部临时文件
- 非归档语义步骤不进入平台侧阶段恢复主路径

详见 [流程开发规范](pipeline-guide.md)。

### 2.3 运行时负责

- 读取阶段资产
- 校验阶段资产是否兼容可复用
- 构建当前阶段图
- 在自动模式下计算最小阶段重跑范围
- 在手动模式下校验指定阶段资产能否继续消费
- 在未命中现有阶段资产时写入新的阶段资产目录

MVP 额外约束：

- stage 不允许直接依赖阶段外 `process` 的输出
- 主数据链路的恢复边界只落在 `named workflow` 阶段上
- 只有真正保留阶段资产的阶段参与恢复判断

## 3. 平台最小接口

平台传给运行时的最小接口：

```text
--reuse-stage <指定 stage.json，可选>
--archive-root <当前运行阶段归档根目录，可选>
```

语义：

- 未传 `reuse-stage`：自动恢复或全量运行，由运行时自行扫描 `archive_root` 判断
- 传 `reuse-stage`：手动恢复
- `archive-root`：
  - 显式指定时，作为当前运行的归档根目录
  - 未指定时，运行时默认使用当前 Nextflow 启动目录下的 `./.nf-stage-archive/`

补充规则：

- 平台不对资产做兼容性判断，只负责传递 `archive_root` 或手动指定的阶段资产
- 平台托管场景下，推荐显式提供共享 `archive_root`
- 用户本地运行场景下，可不提供 `archive_root`
- 当本地归档目录和显式 `archive_root` 同时存在同一阶段资产时，运行时优先使用本地资产

## 4. stage.json Schema

每个阶段资产至少包含一个 `stage.json`：

```json
{
  "schema_version": "v1",
  "archive_id": "bqsr-20260402-001",
  "project_sn": "P2026-001",
  "stage": "BQSR_STAGE",
  "created_at": "2026-04-02T10:00:00+08:00",
  "compatibility": {
    "input_digest": "sha256:...",
    "compatibility_digest": "sha256:...",
    "reference_build": "GRCh38",
    "dependency_versions": {
      "known_sites": "dbsnp-154"
    }
  },
  "integrity": {
    "status": "ok"
  },
  "artifacts": [
    {
      "name": "recal_bam",
      "uri": "artifacts/sample1.recal.bam",
      "checksum": "sha256:...",
      "size": 123456789
    },
    {
      "name": "recal_bai",
      "uri": "artifacts/sample1.recal.bam.bai",
      "checksum": "sha256:...",
      "size": 12345
    }
  ],
  "upstream_stages": [
    {
      "stage": "ALIGN_STAGE",
      "compatibility_digest": "sha256:..."
    }
  ],
  "provenance": {
    "workflow_id": "wf-20260402-001",
    "pipeline_version": "1.3.0",
    "params_digest": "sha256:...",
    "script_hash": "sha256:...",
    "container": "docker://..."
  }
}
```

### 4.1 自动恢复判断必需字段

- `compatibility.input_digest`
- `compatibility.compatibility_digest`
- `compatibility.reference_build`
- `compatibility.dependency_versions`
- `integrity.status`
- `artifacts`

### 4.2 审计字段

- `provenance.workflow_id`
- `provenance.pipeline_version`
- `provenance.params_digest`
- `provenance.script_hash`
- `provenance.container`

说明：

- `archive_id` 仅用于该阶段资产的审计标识，不作为目录主键、复用判断主键或索引主键
- `workflow_id` 保留为来源审计字段，不作为阶段资产目录主键

### 4.3 artifacts 约束

`artifacts` 中每个对象至少包含 `name`、`uri`、`checksum`，建议补充 `size`。

规则：

- `name` 必须与阶段 `emit:` 的命名输出一致
- 默认情况下，`artifacts` 对应阶段 `emit:` 的命名输出
- `stage.json` 只承担单个阶段资产自描述，不承担全局索引职责

### 4.4 upstream_stages 约束

`upstream_stages` 用于记录当前阶段实际依赖的上游阶段资产。

规则：

- 仅记录来自上游阶段 `emit:` 的输入来源
- 不记录 `params`、配置文件、参考资源、数据库等稳定外部输入
- 默认记录 `stage` 和 `compatibility_digest`
- 用于重建阶段资产依赖链和解释上游引用关系

## 5. 兼容性摘要规则

### 5.1 input_digest

描述某阶段本次实际消费的关键输入和关键参数摘要，至少包含：

- 该阶段 `take:` 中会影响结果的关键输入摘要
- 与该阶段相关的关键参数摘要
- `reference_build`
- `dependency_versions`

"关键参数"限定为当前阶段边界内直接消费、且会影响阶段结果的参数，不要求平台推导，也不要求跨阶段传递性分析。

若某阶段关键输入来自上游阶段 `emit:`，则上游复用产物的身份信息也必须进入 `input_digest`，包括上游 `artifacts.name` 和 `artifacts.checksum`。

### 5.2 compatibility_digest

用于判断某阶段历史资产能否被当前运行直接复用，至少基于以下稳定信息规范化计算：

- `stage`
- `take:` / `emit:` 的稳定接口定义
- `input_digest`
- `reference_build`
- `dependency_versions`

使用完整 `sha256` 保存到 `stage.json` 中。

### 5.3 作者侧无需显式版本号

当前主线不要求流程作者显式维护额外阶段版本号。

## 6. 归档路径规则

### 6.1 路径结构

```text
<archive-root>/<pipeline>/<project-sn>/<stage>/<compatibility-digest-prefix>/
```

示例：

```text
s3://bucket/nf-stage-archive/wes-germline/P2026-001/BQSR_STAGE/2cf24dba5fb0a30e/
```

- 路径目录名使用完整 `compatibility_digest` 的前 `16` 位
- `stage.json` 中保留完整 `compatibility_digest`

### 6.2 路径原则

- 阶段资产只读
- 命中相同 `compatibility_digest` 时直接复用已有阶段资产
- 只有未命中现有阶段资产时，才创建新的资产目录
- 不覆盖已存在的阶段资产目录
- `project_sn` 作为项目隔离维度进入路径

### 6.3 两级 archive_root

归档根目录支持两级：

- 显式指定的 `archive_root`
- 默认本地归档目录 `./.nf-stage-archive/`

规则：

- 未指定 `archive_root` 时，默认使用 `./.nf-stage-archive/`
- 本地归档目录只保证当前用户或当前运行环境可用
- 本地与显式 `archive_root` 同时存在可复用阶段资产时，优先使用本地资产
- 自动恢复时，先扫描本地 `archive_root`，再扫描显式 `archive_root`

## 7. 恢复执行流程

### 7.1 自动恢复

1. 运行时先扫描本地 `archive_root`，再按需扫描显式 `archive_root`
2. 从终点阶段开始递归解析阶段依赖
3. 解析某阶段时：
   - 先基于稳定外部输入和已解析出的上游阶段资产，确定该阶段当前实际输入
   - 再计算该阶段当前 `input_digest` 与 `compatibility_digest`
   - 若存在兼容且完整的历史资产，则直接复用该阶段，停止追溯该分支上游
   - 若不存在兼容资产，则标记为需要执行，继续检查上游
4. 递归结束后，收敛出需要执行的最小阶段集合
5. 按阶段依赖顺序运行未满足阶段

### 7.2 手动恢复

1. 用户明确指定一个历史阶段资产
2. 平台将该 `stage.json` 传给运行时
3. 运行时校验：
   - 指定资产的 `stage` 是否与当前接入点一致
   - `artifacts` 是否完整可读
   - 当前流程是否仍可消费这些 `artifacts`
4. 若可消费，则从该阶段下游继续执行
5. 若不可消费，则返回错误或要求重新选择

### 7.3 最小复用条件

某阶段历史资产可直接复用，至少应满足：

- `stage` 一致
- `compatibility.compatibility_digest` 一致
- `integrity.status == ok`
- `artifacts` 完整可读

## 8. 运行时最小接口

建议至少提供以下接口：

- `loadStageArchive(manifestPath)`
- `validateArchiveForReuse(manifestPath)`
- `validateArchiveForConsume(manifestPath)`
- `resolveExecutableStages(assets, terminalStages)`

补充：

- 命中相同 `compatibility_digest` 时，直接复用已有阶段资产
- 只有未命中时，才创建新的阶段资产目录

## 9. CLI 与平台入口

正式用户入口应统一到工作流运行管理 CLI，而不是单独维护一套阶段恢复 CLI。

建议命令域：

```text
nfctl stage ...
nfctl submit ... --reuse-stage <stage_archive>
```

CLI 用于：

- 查看阶段资产
- 校验阶段资产
- 以指定阶段输入启动新运行

## 10. 多集群策略

当前主推荐策略：

- 重跑优先回原集群
- 新项目再按负载调度
- 只有在原集群明显不合适时，才考虑后续阶段边界迁移

阶段边界迁移保留为后续增强能力，不纳入当前主线。

说明：上述策略属于平台调度能力，nf-stage 插件不负责集群选择，只负责阶段资产产出与恢复判断。

## 11. 演进顺序

建议按以下顺序推进：

1. 固定 `stage.json` 最小 schema
2. 固定 `named workflow` 是唯一阶段边界
3. 先支持手动指定阶段资产
4. 再支持自动恢复下的阶段级即时复用判断
5. 最后再优化插件或公共库形态
