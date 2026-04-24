# startStage — 从指定阶段开始执行（提案）

[English](../start-stage.md) | **中文**

状态：设计草案，待后续需要时实现。插件层面的统一能力，区别于 README 三类场景表中"手动跳过前序阶段"（流程作者在 entry workflow 中按参数分支）的做法。

## 1. 场景

集群 A 资源不足，需要将中间阶段的结果同步到集群 B，从该阶段往后继续分析。要求同步数据量尽可能小。

## 2. 配置

```groovy
stage {
    startStage = 'CALL'  // 从 CALL 开始执行，之前的阶段从归档恢复
}
```

`startStage` 指定本次运行的起始阶段，之前的所有阶段从归档恢复（跳过 digest 匹配），不实际执行。

## 3. 执行逻辑

以 `PREPARE → ALIGN → CALL` 流程为例，`startStage = 'CALL'`：

1. 插件初始化时读取 `startStage`，标记目标阶段
2. 拦截 PREPARE：从 stage.json 重建 channel 输出，跳过执行
3. 拦截 ALIGN：同上
4. 拦截 CALL：正常逻辑（计算 digest、查找归档、未命中则执行）

跳过的阶段不计算 digest，直接从 stage.json 中的 `channels` 数据重建输出 channel。stage.json 已存储完整的 channel 名称、类型和 emission 数据，`emitArchivedData()` 可直接复用。

## 4. 需要同步的数据

| 数据 | 大小 | 说明 |
|------|------|------|
| samplesheet CSV | 很小 | `Channel.fromPath()` 要求文件存在 |
| startStage 之前各阶段的 stage.json | 很小 | 仅元数据，用于重建 channel |
| startStage 前一个阶段的归档文件 | 取决于该阶段产出 | startStage 的 process 需要读取这些文件 |

关键点：更上游阶段的归档文件不需要同步。stage.json 中 file 类型元素重建的 Path 对象不会被实际访问，因为消费它们的阶段也被跳过了。`file()` 只创建 Path 引用，不检查文件存在性。

## 5. 实现要点

- 在 `StageConfig` 增加 `startStage` 配置项
- `intercept()` 中判断当前阶段是否在 startStage 之前：若是，直接从 archiveRoot 下对应的 stage.json 恢复，不执行也不归档
- 到达 startStage 后切换回正常逻辑
- 跳过的阶段不写入 `cached-stages.tsv`
