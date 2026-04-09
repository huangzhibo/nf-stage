include { FASTP }  from '../processes/fastp'
include { FASTQC } from '../processes/fastqc'

workflow PREPARE {
    take:
    reads_ch  // tuple val(meta), path(reads)

    main:
    FASTP(reads_ch)
    FASTQC(FASTP.out)

    emit:
    fastq_ch = FASTP.out      // tuple val(meta), path(clean_fq)
    qc_ch    = FASTQC.out     // tuple val(meta), path(qc_report)
}
