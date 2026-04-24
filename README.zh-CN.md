# nf-stage

[English](README.md) | **中文**

Nextflow 插件，提供**阶段级归档与复用**能力。删除 `work/` 后可从归档恢复已完成的阶段，跳过重复计算。

## 前提

需要定制版 Nextflow（含 `WorkflowInterceptor` 扩展点），官方版本暂不支持。

## 快速开始

### 构建

```bash
# 定制版 Nextflow
cd nextflow && make assemble
# 产出: build/releases/nextflow-26.03.1-edge-dist

# nf-stage 插件
cd nf-stage && ./gradlew assemble
# 产出: build/distributions/nf-stage-0.1.0.zip
```

### 部署

```bash
INSTALL_DIR=/shared/nextflow-stage

# Nextflow + 插件
cp build/releases/nextflow-26.03.1-edge-dist $INSTALL_DIR/nextflow
chmod +x $INSTALL_DIR/nextflow
mkdir -p $INSTALL_DIR/plugins/nf-stage-0.1.0
unzip /path/to/nf-stage-0.1.0.zip -d $INSTALL_DIR/plugins/nf-stage-0.1.0/
```

Wrapper 脚本（让普通用户直接调用）：

```bash
#!/bin/bash
BASEDIR="$(cd "$(dirname "$0")" && pwd)"
export NXF_JAVA_HOME="$BASEDIR/amazon-corretto-21.x.x.x-linux-x64"
export NXF_PLUGINS_DIR="$BASEDIR/plugins"
exec "$BASEDIR/nextflow" "$@"
```

## 配置

`nextflow.config`：

```groovy
plugins {
    id 'nf-stage@0.1.0'
}

stage {
    archiveRoot      = '.nf-stage-archive'   // 归档根目录
    cachedStagesFile = 'cached-stages.tsv'   // 复用记录文件
    writable         = true                  // 是否允许写入新归档
}
```

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `archiveRoot` | String | `.nf-stage-archive` | 归档根目录，推荐指向共享存储（NFS/Lustre/S3）|
| `cachedStagesFile` | String | `cached-stages.tsv` | 本次运行中被复用的阶段记录 |
| `writable` | Boolean | `true` | 是否允许写归档；`false` 用于只读复用（详见 [design.md](docs/zh/design.md#一写多读)）|

## 编写可复用的流程

nf-stage 以 named workflow 作为唯一阶段边界：

- 通过 `take:` 声明输入、`emit:` 声明输出
- `emit:` 对应的文件/值是该阶段长期归档的产物
- 阶段内部未进入 `emit:` 的文件会随 `work/` 删除
- 阶段输入应来自上游阶段 `emit:` 或稳定外部输入（`params`、静态样本表），**不要**依赖阶段外 process 输出——普通 process 不参与归档，阶段复用时会触发下游重跑

最小示例：

```nextflow
include { PREPARE } from './workflows/prepare'
include { ALIGN }   from './workflows/align'
include { CALL }    from './workflows/call'

workflow {
    main:
    prepared = PREPARE(params.input)
    aligned  = ALIGN(prepared.fastq_ch, params.reference)
    called   = CALL(aligned.bam_ch, params.reference)

    publish:
    variants = called.vcf_ch
}

output {
    variants { path '.' }
}
```

完整示例参见 [examples/wgs-demo](examples/wgs-demo)。

## 工作原理

拦截每个 named workflow，基于输入内容计算 SHA256 摘要；命中归档直接发射输出，未命中则正常执行并归档。详见 [design.md 复用判断流程](docs/zh/design.md#复用判断流程)。

三类场景：

| 场景 | 机制 | 前提 |
|------|------|------|
| 单次运行失败重跑 | Nextflow 原生 `-resume` | `work/` 仍在 |
| 删 `work/` 后重跑 | nf-stage 阶段复用 | 归档目录仍在 |
| 手动跳过前序阶段 | 流程作者在 entry workflow 中按参数分支 | 用户提供该阶段输入 |

## 产出

归档结构：

```text
<archiveRoot>/<stage>/<digest-prefix-16>/
├── stage.json      # 元数据 + channel 序列化数据
├── 0/              # 第 1 个 emission 的文件
└── 1/              # 第 2 个 emission ...
```

`cached-stages.tsv`：

```
stage   digest              task_count  archive_path                                archived_at
ALIGN   sha256:db6fe4b...   4           .nf-stage-archive/ALIGN/db6fe4bca85f1cf2    2026-04-10T14:06:13+08:00
```

## 更多

- [docs/zh/design.md](docs/zh/design.md) — 设计文档（术语、schema、兼容性摘要、一写多读、未实现提案）

## 开发

```bash
make assemble    # 构建
make test        # 单元测试
make install     # 安装到 ~/.nextflow/plugins/
```
