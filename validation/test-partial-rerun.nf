/*
 * Test: partial stage rerun when downstream params change.
 * PREPARE → ALIGN → CALL_VARIANTS
 *
 * Scenario A: change dbsnp    → PREPARE/ALIGN cached, CALL_VARIANTS reruns
 * Scenario B: change reference → PREPARE cached, ALIGN/CALL_VARIANTS rerun
 */

process FASTP {
    input:  tuple val(meta), path(fastq)
    output: tuple val(meta), path("${meta.id}.trimmed.fq")
    script: "echo 'trimmed: '\$(cat ${fastq}) > ${meta.id}.trimmed.fq"
}

process BWA_MEM {
    input:  tuple val(meta), path(fastq); val(reference)
    output: tuple val(meta), path("${meta.id}.bam")
    script: "echo 'aligned '\$(cat ${fastq})' to ${reference}' > ${meta.id}.bam"
}

process HAPLOTYPE_CALLER {
    input:  tuple val(meta), path(bam); val(dbsnp)
    output: tuple val(meta), path("${meta.id}.vcf")
    script: "echo 'called '\$(cat ${bam})' with ${dbsnp}' > ${meta.id}.vcf"
}

workflow PREPARE {
    take: input
    main: FASTP(input)
    emit: fastq = FASTP.out
}

workflow ALIGN {
    take: fastq; reference
    main: BWA_MEM(fastq, reference)
    emit: bam = BWA_MEM.out
}

workflow CALL_VARIANTS {
    take: bam; dbsnp
    main: HAPLOTYPE_CALLER(bam, dbsnp)
    emit: vcf = HAPLOTYPE_CALLER.out
}

workflow {
    ch = Channel.of(
        [[id: 'S1'], file('data/sample1.fq')],
        [[id: 'S2'], file('data/sample2.fq')]
    )
    prepared = PREPARE(ch)
    aligned  = ALIGN(prepared.fastq, params.reference)
    variants = CALL_VARIANTS(aligned.bam, params.dbsnp)
    variants.vcf.view { meta, vcf -> "${meta.id}: ${vcf}" }
}
