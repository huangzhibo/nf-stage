process SAMTOOLS_SORT {
    input:
    tuple val(meta), path(sam)

    output:
    tuple val(meta), path("${meta.id}.sorted.bam"), path("${meta.id}.sorted.bam.bai")

    script:
    """
    echo "sorted: \$(cat ${sam})" > ${meta.id}.sorted.bam
    echo "index of ${meta.id}" > ${meta.id}.sorted.bam.bai
    """
}
