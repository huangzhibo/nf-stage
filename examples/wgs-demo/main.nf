/*
 * WGS Demo Pipeline
 *
 * 演示符合开发规范的阶段化流程结构。
 * 所有工具调用用 echo 模拟，可直接运行验证阶段归档与恢复。
 *
 * 阶段链路: PREPARE → ALIGN → CALL
 *
 * 运行方式：
 *   从头开始: nextflow run main.nf -c consumer.config
 *   从 CALL 开始: nextflow run main.nf -c consumer.config --bam_input data/bamsheet.csv
 */

include { PREPARE } from './workflows/prepare'
include { ALIGN }   from './workflows/align'
include { CALL }    from './workflows/call'

workflow {
    main:
    if (params.bam_input) {
        // 从已有 BAM 开始，跳过 PREPARE 和 ALIGN
        bam_ch = Channel.fromPath(params.bam_input)
            .splitCsv(header: true, strip: true)
            .filter { row -> row.sample }
            .map { row -> tuple([id: row.sample], file(row.bam), file(row.bai)) }
    }
    else {
        reads_ch = Channel.fromPath(params.input)
            .splitCsv(header: true, strip: true)
            .filter { row -> row.sample }
            .map { row -> tuple([id: row.sample], file(row.reads)) }

        prepared = PREPARE(reads_ch)
        aligned  = ALIGN(prepared.fastq_ch, params.reference)
        bam_ch   = aligned.bam_ch
    }

    called = CALL(bam_ch, params.reference)

    publish:
    variants = called.vcf_ch
}

output {
    variants {
    }
}
