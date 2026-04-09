process FASTP {
    input:
    tuple val(meta), path(reads)

    output:
    tuple val(meta), path("${meta.id}.clean.fq")

    script:
    """
    echo "fastp: filtering ${reads}" > ${meta.id}.clean.fq
    cat ${reads} >> ${meta.id}.clean.fq
    """
}
