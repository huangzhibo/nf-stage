process FASTQC {
    input:
    tuple val(meta), path(reads)

    output:
    tuple val(meta), path("${meta.id}_fastqc.txt")

    script:
    """
    echo "fastqc report for ${meta.id}" > ${meta.id}_fastqc.txt
    wc -l < ${reads} >> ${meta.id}_fastqc.txt
    """
}
