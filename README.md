# nf-stage 插件使用指南

## 功能

nf-stage 为 Nextflow 提供阶段级归档与恢复能力。删除 `work/` 目录后，可从归档中恢复已完成的阶段，跳过重复计算。

## 前提

需要定制版 Nextflow（含 WorkflowInterceptor 扩展点），官方版本暂不支持。

## 安装

### 1. 构建

```bash
# 定制版 Nextflow
cd nextflow && make assemble
# 产出: build/releases/nextflow-26.03.1-edge-dist

# nf-stage 插件
cd nf-stage && ./gradlew assemble
# 产出: build/distributions/nf-stage-0.1.0.zip
```

### 2. 部署到 HPC

```bash
# 选一个共享目录作为安装根目录
INSTALL_DIR=/shared/nextflow-stage

# Java 21
curl -LO https://corretto.aws/downloads/latest/amazon-corretto-21-x64-linux-jdk.tar.gz
tar xzf amazon-corretto-21-x64-linux-jdk.tar.gz -C $INSTALL_DIR/

# Nextflow 可执行文件
cp build/releases/nextflow-26.03.1-edge-dist $INSTALL_DIR/nextflow
chmod +x $INSTALL_DIR/nextflow

# 插件
mkdir -p $INSTALL_DIR/plugins/nf-stage-0.1.0
cd $INSTALL_DIR/plugins/nf-stage-0.1.0
unzip /path/to/nf-stage-0.1.0.zip
```

### 3. Wrapper 脚本

创建 `$INSTALL_DIR/nextflow-stage`，任何账号都可直接调用：

```bash
#!/bin/bash
BASEDIR="$(cd "$(dirname "$0")" && pwd)"
export NXF_JAVA_HOME="$BASEDIR/amazon-corretto-21.x.x.x-linux-x64"
export NXF_PLUGINS_DIR="$BASEDIR/plugins"
exec "$BASEDIR/nextflow" "$@"
```

```bash
chmod +x $INSTALL_DIR/nextflow-stage
```

使用方式与官方 Nextflow 完全一致，不影响现有 Nextflow 环境：

```bash
/shared/nextflow-stage/nextflow-stage run main.nf
```

## 配置

`nextflow.config`:

```groovy
plugins {
    id 'nf-stage@0.1.0'
}

stage {
    archiveRoot = '.nf-stage-archive'     // 归档目录，默认值
    cachedStagesFile = 'cached-stages.tsv' // 缓存记录文件，默认值
}
```

`archiveRoot` 应设为计算节点间共享的路径（NFS/Lustre），以便归档文件在恢复时可访问。

## 流程要求

阶段以 named workflow 定义，入口 workflow 编排阶段：

```nextflow
include { ALIGN } from './workflows/align'
include { CALL }  from './workflows/call'

workflow {
    aligned = ALIGN(params.input, params.reference)
    result  = CALL(aligned.bam_ch, params.reference)
}
```

不需要在流程中编写任何归档/恢复逻辑，插件自动处理。

## 工作原理

1. **首次运行**：拦截每个 named workflow，计算输入摘要（SHA256），正常执行并将输出归档到 `archiveRoot/<stage>/<digest>/`
2. **后续运行**：相同输入摘要命中归档时，直接从归档恢复输出，跳过该阶段的所有 process
3. **归档失败**：自动 fallback 到正常执行，不影响流程运行

## 产出文件

- `<archiveRoot>/<stage>/<digest>/stage.json`：归档元数据，含通道结构、文件校验和、task hash
- `cached-stages.tsv`：本次运行中从归档恢复的阶段记录，供平台读取

`cached-stages.tsv` 格式：

```
stage	digest	task_count	archive_path	archived_at
ALIGN	sha256:abc123...	5	.nf-stage-archive/ALIGN/sha256:abc123...	2025-04-09T10:00:00
```

## 注意事项

- 插件仅在 `nextflow.config` 中声明后才会加载，不声明不影响现有流程
- 每次运行会清空上次的 `cached-stages.tsv`，不会残留旧数据
- 阶段输出文件变更（内容不同）会产生不同的 digest，自动触发重新执行

## 开发

```bash
make assemble  # 构建
make test      # 运行单元测试
make install   # 安装到本地 ~/.nextflow/plugins/
```
