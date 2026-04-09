process HAPLOTYPE_CALLER {
    input:
    tuple val(meta), path(bam), path(bai)
    val reference

    output:
    tuple val(meta), path("${meta.id}.g.vcf")

    script:
    """
    echo "GVCF ${meta.id} ref=${reference}" > ${meta.id}.g.vcf
    wc -c < ${bam} >> ${meta.id}.g.vcf
    """
}
