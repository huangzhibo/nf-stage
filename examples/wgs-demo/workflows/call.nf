include { HAPLOTYPE_CALLER } from '../processes/haplotype_caller'

workflow CALL {
    take:
    bam_ch     // tuple val(meta), path(bam), path(bai)
    reference  // val

    main:
    HAPLOTYPE_CALLER(bam_ch, reference)

    emit:
    vcf_ch = HAPLOTYPE_CALLER.out  // tuple val(meta), path(gvcf)
}
