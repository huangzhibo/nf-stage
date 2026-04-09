include { BWA_MEM }       from '../processes/bwa_mem'
include { SAMTOOLS_SORT } from '../processes/samtools_sort'

workflow ALIGN {
    take:
    fastq_ch   // tuple val(meta), path(clean_fq)
    reference  // val

    main:
    BWA_MEM(fastq_ch, reference)
    SAMTOOLS_SORT(BWA_MEM.out)

    emit:
    bam_ch = SAMTOOLS_SORT.out  // tuple val(meta), path(bam), path(bai)
}
