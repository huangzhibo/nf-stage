/*
 * WGS Demo Pipeline
 *
 * 演示符合开发规范的阶段化流程结构。
 * 所有工具调用用 echo 模拟，可直接运行验证阶段归档与恢复。
 *
 * 阶段链路: PREPARE → ALIGN → CALL
 */

include { PREPARE } from './workflows/prepare'
include { ALIGN }   from './workflows/align'
include { CALL }    from './workflows/call'

workflow {
    main:
    reads_ch = Channel.of(
        [[id: 'SAMPLE1'], file('data/sample1.fq')],
        [[id: 'SAMPLE2'], file('data/sample2.fq')]
    )

    prepared = PREPARE(reads_ch)
    aligned  = ALIGN(prepared.fastq_ch, params.reference)
    called   = CALL(aligned.bam_ch, params.reference)

    publish:
    variants = called.vcf_ch
}

output {
    variants {
    }
}
