package nextflow.plugin

import spock.lang.Specification

class StageConfigTest extends Specification {

    def 'default constructor should leave all fields null' () {
        when:
        def config = new StageConfig()

        then:
        config.archiveRoot == null
        config.cachedStagesFile == null
        config.writable == null
    }

    def 'map constructor should populate all fields' () {
        when:
        def config = new StageConfig([
            archiveRoot     : '/tmp/archive',
            cachedStagesFile: 'my-cache.tsv',
            writable        : false,
        ])

        then:
        config.archiveRoot == '/tmp/archive'
        config.cachedStagesFile == 'my-cache.tsv'
        config.writable == false
    }

    def 'map constructor should leave missing keys as null (defaults applied elsewhere)' () {
        when:
        def config = new StageConfig([:])

        then:
        config.archiveRoot == null
        config.cachedStagesFile == null
        config.writable == null
    }

    def 'map constructor should accept writable=true' () {
        when:
        def config = new StageConfig([writable: true])

        then:
        config.writable == true
    }

    def 'map constructor should ignore unknown keys' () {
        when:
        def config = new StageConfig([
            archiveRoot: '/a',
            unknownKey : 'ignored',
        ])

        then:
        config.archiveRoot == '/a'
        config.cachedStagesFile == null
        config.writable == null
    }
}
