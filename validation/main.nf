workflow PREPARE {
    take: input
    main:
        result = input.map { it.toUpperCase() }
    emit:
        result
}

workflow PROCESS_DATA {
    take: data
    main:
        result = data.map { "processed: ${it}" }
    emit:
        result
}

workflow {
    ch = Channel.of('sample1', 'sample2', 'sample3')
    prepared = PREPARE(ch)
    processed = PROCESS_DATA(prepared.result)
    processed.result.view()
}
