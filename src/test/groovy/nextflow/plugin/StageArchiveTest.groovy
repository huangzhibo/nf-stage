package nextflow.plugin

import java.nio.file.Files
import java.nio.file.Path

import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

class StageArchiveTest extends Specification {

    @TempDir
    Path tempDir

    StageArchive archive

    def setup() {
        archive = new StageArchive(tempDir)
    }

    // -- archivePath --

    def 'archivePath should use first 16 chars of digest hex' () {
        expect:
        archive.archivePath('ALIGN', 'sha256:abcdef1234567890aaaa')
            == tempDir.resolve('ALIGN/abcdef1234567890')
    }

    def 'archivePath should strip sha256 prefix' () {
        expect:
        archive.archivePath('STAGE', 'sha256:1234567890abcdef1111')
            == tempDir.resolve('STAGE/1234567890abcdef')
    }

    def 'archivePath should handle short digest' () {
        expect:
        archive.archivePath('STAGE', 'sha256:abc')
            == tempDir.resolve('STAGE/abc')
    }

    // -- findArchive --

    def 'findArchive should return null when no archive exists' () {
        expect:
        archive.findArchive('ALIGN', 'sha256:abcdef1234567890aaaa') == null
    }

    def 'findArchive should return data when valid archive exists' () {
        given:
        def path = archive.archivePath('ALIGN', 'sha256:abcdef1234567890aaaa')
        Files.createDirectories(path)
        Files.write(path.resolve('stage.json'), '{"integrity":{"status":"ok"},"stage":"ALIGN"}'.getBytes())

        when:
        def result = archive.findArchive('ALIGN', 'sha256:abcdef1234567890aaaa')

        then:
        result != null
        result.stage == 'ALIGN'
        result.integrity.status == 'ok'
    }

    def 'findArchive should return null when integrity status is not ok' () {
        given:
        def path = archive.archivePath('ALIGN', 'sha256:abcdef1234567890aaaa')
        Files.createDirectories(path)
        Files.write(path.resolve('stage.json'), '{"integrity":{"status":"corrupted"}}'.getBytes())

        expect:
        archive.findArchive('ALIGN', 'sha256:abcdef1234567890aaaa') == null
    }

    def 'findArchive should return null when stage.json is invalid JSON' () {
        given:
        def path = archive.archivePath('ALIGN', 'sha256:abcdef1234567890aaaa')
        Files.createDirectories(path)
        Files.write(path.resolve('stage.json'), 'not json'.getBytes())

        expect:
        archive.findArchive('ALIGN', 'sha256:abcdef1234567890aaaa') == null
    }

    // -- serializeValue / rebuildValue 往返测试 --

    def 'serialize and rebuild single value' () {
        given:
        def itemDir = tempDir.resolve('items/0')

        when:
        def serialized = StageArchive.invokeMethod('serializeValue', [42, itemDir] as Object[]) as List<Map>

        then:
        serialized.size() == 1
        serialized[0].type == 'value'
        serialized[0].data == 42

        when:
        def rebuilt = StageArchive.rebuildValue(serialized, itemDir)

        then:
        rebuilt == 42
    }

    def 'serialize and rebuild map value' () {
        given:
        def meta = [id: 'S1', type: 'WGS']
        def itemDir = tempDir.resolve('items/0')

        when:
        def serialized = StageArchive.invokeMethod('serializeValue', [meta, itemDir] as Object[]) as List<Map>

        then:
        serialized.size() == 1
        serialized[0].type == 'value'
        serialized[0].data == [id: 'S1', type: 'WGS']

        when:
        def rebuilt = StageArchive.rebuildValue(serialized, itemDir)

        then:
        rebuilt == [id: 'S1', type: 'WGS']
    }

    def 'serialize and rebuild single file' () {
        given:
        def sourceDir = tempDir.resolve('source')
        Files.createDirectories(sourceDir)
        def sourceFile = sourceDir.resolve('test.bam')
        Files.write(sourceFile, 'bam content'.getBytes())
        def itemDir = tempDir.resolve('items/0')

        when:
        def serialized = StageArchive.invokeMethod('serializeValue', [sourceFile, itemDir] as Object[]) as List<Map>

        then:
        serialized.size() == 1
        serialized[0].type == 'file'
        serialized[0].name == 'test.bam'
        serialized[0].checksum.startsWith('sha256:')
        serialized[0].size == 11
        Files.exists(itemDir.resolve('test.bam'))

        when:
        def rebuilt = StageArchive.rebuildValue(serialized, itemDir)

        then:
        rebuilt instanceof Path
        (rebuilt as Path).fileName.toString() == 'test.bam'
        Files.readString(rebuilt as Path) == 'bam content'
    }

    def 'serialize and rebuild tuple [meta, file1, file2]' () {
        given:
        def sourceDir = tempDir.resolve('source')
        Files.createDirectories(sourceDir)
        Files.write(sourceDir.resolve('S1.bam'), 'bam data'.getBytes())
        Files.write(sourceDir.resolve('S1.bam.bai'), 'index data'.getBytes())
        def itemDir = tempDir.resolve('items/0')

        def tuple = [[id: 'S1'], sourceDir.resolve('S1.bam'), sourceDir.resolve('S1.bam.bai')]

        when:
        def serialized = StageArchive.invokeMethod('serializeValue', [tuple, itemDir] as Object[]) as List<Map>

        then:
        serialized.size() == 3
        serialized[0].type == 'value'
        serialized[0].data == [id: 'S1']
        serialized[1].type == 'file'
        serialized[1].name == 'S1.bam'
        serialized[2].type == 'file'
        serialized[2].name == 'S1.bam.bai'

        when:
        def rebuilt = StageArchive.rebuildValue(serialized, itemDir)

        then:
        rebuilt instanceof List
        (rebuilt as List).size() == 3
        (rebuilt as List)[0] == [id: 'S1']
        ((rebuilt as List)[1] as Path).fileName.toString() == 'S1.bam'
        ((rebuilt as List)[2] as Path).fileName.toString() == 'S1.bam.bai'
        Files.readString((rebuilt as List)[1] as Path) == 'bam data'
    }

    def 'serialize and rebuild preserves tuple element order' () {
        given:
        def sourceDir = tempDir.resolve('source')
        Files.createDirectories(sourceDir)
        Files.write(sourceDir.resolve('data.txt'), 'content'.getBytes())
        def itemDir = tempDir.resolve('items/0')

        // non-standard order: file, meta, file
        def tuple = [sourceDir.resolve('data.txt'), [id: 'S1'], sourceDir.resolve('data.txt')]

        when:
        def serialized = StageArchive.invokeMethod('serializeValue', [tuple, itemDir] as Object[]) as List<Map>

        then:
        serialized[0].type == 'file'
        serialized[1].type == 'value'
        serialized[2].type == 'file'

        when:
        def rebuilt = StageArchive.rebuildValue(serialized, itemDir)

        then:
        (rebuilt as List)[0] instanceof Path
        (rebuilt as List)[1] == [id: 'S1']
        (rebuilt as List)[2] instanceof Path
    }

    // -- copyWithChecksum --

    def 'copyWithChecksum should copy file and return sha256' () {
        given:
        def source = tempDir.resolve('src.txt')
        def target = tempDir.resolve('dst.txt')
        Files.write(source, 'hello world'.getBytes())

        when:
        def checksum = StageArchive.copyWithChecksum(source, target)

        then:
        Files.exists(target)
        Files.readString(target) == 'hello world'
        checksum.startsWith('sha256:')
        checksum.length() > 10
    }

    def 'copyWithChecksum should produce consistent checksums' () {
        given:
        def source = tempDir.resolve('src.txt')
        def target1 = tempDir.resolve('dst1.txt')
        def target2 = tempDir.resolve('dst2.txt')
        Files.write(source, 'same content'.getBytes())

        when:
        def checksum1 = StageArchive.copyWithChecksum(source, target1)
        def checksum2 = StageArchive.copyWithChecksum(source, target2)

        then:
        checksum1 == checksum2
    }

    // -- patchTaskHashes --

    def 'patchTaskHashes should add task_hashes to existing stage.json' () {
        given:
        def path = archive.archivePath('ALIGN', 'sha256:abcdef1234567890aaaa')
        Files.createDirectories(path)
        Files.write(path.resolve('stage.json'), '{"stage":"ALIGN","integrity":{"status":"ok"}}'.getBytes())

        when:
        archive.patchTaskHashes('ALIGN', 'sha256:abcdef1234567890aaaa', ['ab/123456', 'cd/789012'])

        then:
        def data = new JsonSlurper().parse(path.resolve('stage.json').toFile()) as Map
        data.task_hashes == ['ab/123456', 'cd/789012']
        data.stage == 'ALIGN'
    }

    def 'patchTaskHashes should not fail when stage.json does not exist' () {
        when:
        archive.patchTaskHashes('MISSING', 'sha256:0000000000000000aaaa', ['ab/123456'])

        then:
        noExceptionThrown()
    }
}
