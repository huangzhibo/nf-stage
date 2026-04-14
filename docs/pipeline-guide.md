# 百趣生信流程开发规范（Nextflow版）

## 1. 目的与适用范围

本规范用于统一百趣生信 Nextflow 流程的开发方式，适用于团队自研、长期维护的 Nextflow DSL2 流程。目标是：

- 提升可读性、可维护性和可复用性
- 保证流程可迁移
- 保证流程支持 Nextflow 原生 `-resume`
- 与 lims2 云平台适配
- 与 nf-stage 插件适配，实现阶段归档与复用（详见第 2 节）

## 2. nf-stage：阶段归档与复用

nf-stage 是团队自研的 Nextflow 插件，核心能力：

前提：需要使用包含 `WorkflowInterceptor` 扩展点的定制版 Nextflow，官方版本暂不支持。

- **阶段归档**：自动拦截每个 named workflow，将其输出归档到 `archiveRoot/<stage>/<digest-prefix>/`
- **阶段复用**：后续运行时，若输入摘要匹配归档，直接发射归档数据，跳过该阶段所有 process
- **自动摘要匹配**：基于内容 SHA256 计算输入摘要，输入不变则命中归档

价值：删除 `work/` 后仍可从归档恢复已完成的阶段，降低存储成本，支持售后重分析。

流程作者的参与方式：

- 按本规范编写流程（正确切分 named workflow 作为阶段）
- 在 `nextflow.config` 中声明 `plugins { id 'nf-stage@0.1.0' }`
- **不需要编写任何归档/复用代码**，插件自动完成
- 阶段实现变更时，可通过增加或调整 named workflow 显式入参 `stage_version`，主动避免使用该阶段旧归档

没有插件时，流程仍然是合法的 Nextflow 流程，只是失去阶段归档与复用能力。

### 安装与部署

详见 [README.md](../README.md)。

## 3. 总体原则

- **约定优于技巧**：优先使用团队统一约定
- **显式优于隐式**：输入、输出、依赖、阶段边界都要显式表达
- **简单优于灵活**：优先选择简单、稳定、可审查的写法
- **可恢复优于一次性可跑**：能稳定 `-resume`，并支持 nf-stage 阶段复用

## 4. 项目结构

推荐结构：

```text
main.nf
nextflow.config
consumer.config
workflows/
  prepare.nf
  align.nf
  bqsr.nf
  hc.nf
processes/
  fastp.nf
  fastqc.nf
  bwa_mem.nf
  sort.nf
  base_recalibrator.nf
  haplotype_caller.nf
data/
```

要求：

- `workflow` 与 `process` 分文件保存
- entry workflow 只负责编排

推荐层次：

- `entry workflow`：流程入口
- `named workflow`：阶段边界
- `process`：单步工具封装

配置分层：

- `nextflow.config`：稳定配置，随代码版本管理
- `consumer.config`：业务配置，通过 `-c consumer.config` 传入

## 5. 编写规范

### 5.1 Workflow

职责：

- 描述依赖关系
- 串联数据流
- 定义阶段边界

禁止：

- 复杂 Groovy 逻辑
- 大量业务判断混写
- 过深的 `map` / `collect` / `branch` 闭包链

复杂逻辑下沉到 `process`、独立脚本或工具程序。

### 5.2 阶段

统一约定：

- `named workflow = 阶段`

要求：

- 通过 `take:` 声明输入
- 通过 `emit:` 声明输出
- 阶段边界有业务意义或复用价值

建议：

- 阶段输入应优先来自上游阶段 `emit:` 或稳定外部输入（`params` 派生值、静态样本表、显式构造的 channel）
- 避免依赖阶段外普通 process 的输出；普通 process 没有归档与复用能力，阶段复用时会重新执行，若输出变化会触发下游阶段也重跑
- 跨阶段数据应通过 channel 传递，不要在代码或中间文件中保存 `work/` 目录下的绝对路径；阶段复用时，下游接收到的输入路径会变为归档路径，而不是首次运行时的 `work/` 路径

如果流程需要支持从中间阶段开始（例如用户已有 BAM 文件，跳过前序阶段），在 entry workflow 中通过参数判断：

```nextflow
workflow {
    main:
    if (params.bam_input) {
        bam_ch = Channel.fromPath(params.bam_input)
            .splitCsv(header: true)
            .map { row -> tuple([id: row.sample], file(row.bam), file(row.bai)) }
    } else {
        prepared = PREPARE(reads_ch)
        aligned  = ALIGN(prepared.fastq_ch, params.reference)
        bam_ch   = aligned.bam_ch
    }
    called = CALL(bam_ch, params.reference)
}
```

### 5.3 Process

职责：封装单个工具或稳定步骤。

要求：

- 输入显式，来源于 `params` 或上游输出
- 成功返回 `0`，失败返回非 `0`

禁止：

- 拼接固定路径读取中间数据
- 绕过 channel 依赖隐式目录
- 在 process 中承担全局流程控制

### 5.4 输出发布

流程输出分为两类：

- **最终交付结果**：面向用户交付，通过 workflow outputs 发布
- **阶段输出**：面向阶段归档与复用，由 nf-stage 插件管理

要求：

- 最终交付结果通过 workflow outputs（`publish:` + `output {}`）发布
- 不要把所有中间产物都暴露为最终输出
- 未进入 `emit:` 的阶段内部文件视为临时文件

## 6. 配置规范

### 6.1 路径与参数

- 所有路径通过 `params` 或环境变量传入
- 最终输出目录通过 `-output-dir` CLI 参数或 `outputDir` 配置项控制（workflow outputs 机制）
- process 只在 task workdir 内产出文件
- 禁止直接读写共享绝对目录充当隐式缓存
- 工具级参数优先通过配置传递

### 6.2 容器与环境

生产流程应考虑：

- 容器化（每个 process 明确容器镜像）
- 资源配置（CPU、内存、磁盘）
- 环境差异隔离（开发/测试/生产）

### 6.3 错误处理

生产流程应配置合理的错误处理策略，避免偶发故障导致整体失败。

### 6.4 发展方向

- 流程版本控制：使用 git 管理流程代码
- 软件与环境版本控制：使用 apptainer 统一打包工具链与运行环境

## 7. 运行质量与验收

### 7.1 Resume 与阶段复用

三类场景要区分：

| 场景 | 机制 | 前提 | 作者责任 |
|------|------|------|---------|
| 单次运行失败重跑 | Nextflow 原生 `-resume` | `work/` 仍在 | 无 |
| 删除 `work/` 后重跑 | nf-stage 阶段复用 | 归档目录仍在 | 无 |
| 从中间阶段直接开始 | 流程编排逻辑 | 用户提供该阶段的输入文件 | 在 entry workflow 中按参数分支（见 5.2） |

开发完成后至少通过以下测试：

- `-resume` 场景：失败后重跑，已完成 task 正确跳过
- 阶段复用场景（删 `work/`，保留归档）：
  - 输入不变 → 所有阶段从归档复用
  - 改末端阶段参数 → 前序复用，末端重跑
  - 改中间阶段参数 → 从受影响阶段开始重跑
  - 复用后的结果与全新运行一致

### 7.2 资源利用率

- 流程 `cpuEfficiency` 应大于 `50%`
- 若无特殊说明，以 workflow 级统计为准
- 使用有代表性的实际数据集验收

可通过以下接口查看：

```bash
curl -X 'GET' \
  'https://api-v1.lims2.com/nf-monitor/workflow/${WORKFLOW_ID}/resource-stats?excludeCached=false' \
  -H 'accept: application/json'
```

### 7.3 小文件控制

当 process 产生巨量临时小文件时：

- 避免对其逐文件做元数据操作（如 stat、ls、chmod），改为批量处理或打包
- 优先写入本地存储（`/tmp` 或 `scratch true`），减少共享文件系统压力
- 最终结果应打包或汇总后再输出
- 目录型 `emit` 超过 `1000` 个文件时，应先在阶段内部收敛

## 8. 附录：最小阶段化示例

```nextflow
include { PREPARE } from './workflows/prepare'
include { ALIGN }   from './workflows/align'
include { BQSR }    from './workflows/bqsr'
include { HC }      from './workflows/hc'

workflow {
    main:
    prepared = PREPARE(params.input)
    aligned  = ALIGN(prepared.fastq_ch, params.reference)
    bqsr     = BQSR(aligned.bam_ch, params.known_sites)
    hc       = HC(bqsr.bam_ch, params.reference)

    publish:
    variants = hc.vcf_ch
}

output {
    variants {
        path '.'
    }
}
```

示例要点：

- entry workflow 简洁
- 阶段边界明确
- 参数显式传递
- 最终结果通过 workflow outputs 发布
- `path '.'` 表示发布到输出目录根下

完整示例项目参见 [examples/wgs-demo](../examples/wgs-demo)。

## 9. 附录：上海集群部署信息

- 修改版 Nextflow：`/home/lims/software/bin/nextflow-biotree`
- 运行示例：`/home/lims/workitems/00.nf-stage/test/run.sh`
