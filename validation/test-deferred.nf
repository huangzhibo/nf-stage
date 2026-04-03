/*
 * Test: deferred execution - verify if proceed() can be called
 * from within an addIgniter closure (after script parsing).
 */

process STEP_A {
    input:  tuple val(meta), path(fastq)
    output: tuple val(meta), path("${meta.id}.out")
    script: "echo 'processed '\$(cat ${fastq}) > ${meta.id}.out"
}

workflow STAGE_A {
    take: input
    main: STEP_A(input)
    emit: result = STEP_A.out
}

workflow {
    ch = Channel.of(
        [[id: 'S1'], file('data/sample1.fq')],
        [[id: 'S2'], file('data/sample2.fq')]
    )
    result = STAGE_A(ch)
    result.result.view { meta, f -> "${meta.id}: ${f}" }
}
