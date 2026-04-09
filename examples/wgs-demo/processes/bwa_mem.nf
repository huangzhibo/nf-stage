process BWA_MEM {
    input:
    tuple val(meta), path(reads)
    val reference

    output:
    tuple val(meta), path("${meta.id}.sam")

    script:
    """
    echo "bwa mem -R ${meta.id} ${reference}" > ${meta.id}.sam
    cat ${reads} >> ${meta.id}.sam
    """
}
