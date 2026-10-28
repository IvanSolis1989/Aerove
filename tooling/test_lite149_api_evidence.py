"""Offline rejection and identity tests; no download, SDK invocation or APK build."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest import mock
import zipfile

import lite149_api_evidence as audit


class Lite149ApiEvidenceTest(unittest.TestCase):
    def test_exact_run_and_scope_only(self):
        self.assertEqual(audit.request(json.dumps(audit.CONTRACT['request'])), audit.CONTRACT['request'])
        for delta in (dict(runId=40627739663), dict(attempt=2), dict(mode='select', memberPaths=['BUILD_INFO.json']),
                      dict(pathContains='LayoutInfo'), dict(headSHA='a'*40), dict(artifactId=12790881918)):
            with self.subTest(delta=delta), self.assertRaises(ValueError):
                audit.request(json.dumps(dict(audit.CONTRACT['request'], **delta)))

    def test_usage_explicit_removal_and_absence_are_distinct(self):
        method = audit.CONTRACT['methods'][0]
        for text in (method['owner']+'\n', method['owner']+':\n    public java.util.List getModifierInfo()\n'):
            self.assertTrue(audit.usage_results(text, [method])[0]['explicitlyRemoved'])
        result = audit.usage_results(method['owner']+':\n    private int unrelated()\n', [method])[0]
        self.assertFalse(result['explicitlyRemoved'])
        self.assertNotIn('survives', result)

    def test_mapping_requires_exact_return_parameters_and_actual_definition(self):
        method = dict(owner='original.Owner', name='getValue', returns='original.Value', parameters=[])
        mapping = 'original.Owner -> a.b:\n    1:1:original.Value getValue():2:2 -> c\noriginal.Value -> a.d:\n'
        target = audit.mapping_targets(mapping, [method])[0]
        self.assertEqual(('a.b', ['c'], '()La/d;'), (target['mappedOwner'], target['mappedNames'], target['descriptor']))
        self.assertEqual(['c()La/d;'], audit.definition_results('.method public abstract c()La/d;\n.end method\n', [target])[0]['definitionCandidates'])
        for smali in ('.method public c(I)La/d;\n.end method', '.method public c()I\n.end method',
                      ' invoke-interface {v0}, La/b;->c()La/d;\n'):
            self.assertEqual([], audit.definition_results(smali, [target])[0]['definitionCandidates'])

    def test_inline_frame_and_other_return_type_do_not_prove_callable_method(self):
        method = dict(owner='original.Owner', name='getValue', returns='int', parameters=[])
        mapping = 'original.Owner -> a.b:\n    1:1:int other.Owner.getValue():2:2 -> c\n    2:2:long getValue():3:3 -> d\n'
        target = audit.mapping_targets(mapping, [method])[0]
        self.assertEqual([], target['mappedNames'])
        self.assertEqual([], audit.definition_results('.method public c()I\n.method public d()J\n', [target])[0]['definitionCandidates'])

    def test_same_owner_inline_frame_does_not_own_caller_definition(self):
        method = dict(owner='androidx.compose.ui.semantics.SemanticsNode', name='getId', returns='int', parameters=[])
        mapping = method['owner'] + ' -> a.b:\n    1:1:int getId():12:12 -> c\n    1:1:int unrelated():20 -> c\n'
        target = audit.mapping_targets(mapping, [method])[0]
        self.assertEqual([], target['mappedNames'])
        self.assertEqual('MISSING_OR_AMBIGUOUS_OUTER_MAPPING', target['mappingStatus'])
        self.assertEqual([], audit.definition_results('.method public c()I\n.end method\n', [target])[0]['definitionCandidates'])

    def test_missing_original_signature_never_falls_back_to_reused_name(self):
        method = dict(owner='androidx.compose.ui.semantics.SemanticsNode', name='getId', returns='int', parameters=[])
        mapping = method['owner'] + ' -> a.b:\n    int unrelated() -> getId\n'
        target = audit.mapping_targets(mapping, [method])[0]
        self.assertEqual([], target['mappedNames'])
        self.assertEqual([], audit.definition_results('.method public getId()I\n.end method\n', [target])[0]['definitionCandidates'])

    def test_direct_noninline_mapping_can_keep_original_name(self):
        method = dict(owner='original.Owner', name='getId', returns='int', parameters=[])
        target = audit.mapping_targets('original.Owner -> a.b:\n    int getId() -> getId\n', [method])[0]
        self.assertEqual(['getId'], target['mappedNames'])
        self.assertEqual('CONFIRMED_OUTER_METHOD', target['mappingStatus'])
        self.assertEqual(['getId()I'], audit.definition_results('.method public getId()I\n.end method\n', [target])[0]['definitionCandidates'])

    def test_final_outer_frame_owns_method_across_multiple_line_groups(self):
        methods = [dict(owner='original.Owner', name=name, returns='int', parameters=[]) for name in ('getId', 'nested', 'deep')]
        mapping = ('original.Owner -> a.b:\n    1:1:int getId():1:1 -> c\n'
                   '    2:4:int nested():2:4 -> c\n    2:4:int getId():5 -> c\n'
                   '    5:5:int deep():7:7 -> c\n    5:5:int nested():8 -> c\n    5:5:int getId():5 -> c\n')
        targets = audit.mapping_targets(mapping, methods)
        self.assertEqual([['c'], [], []], [row['mappedNames'] for row in targets])
        self.assertEqual([['c()I'], [], []], [row['definitionCandidates'] for row in audit.definition_results('.method public c()I\n.end method\n', targets)])

    def test_alias_with_different_outer_signatures_is_ambiguous(self):
        method = dict(owner='original.Owner', name='getId', returns='int', parameters=[])
        for rows in ('    1:1:int getId():1:1 -> c\n    2:2:int unrelated():2:2 -> c\n',
                     '    int getId() -> c\n    int unrelated() -> c\n'):
            with self.subTest(rows=rows):
                target = audit.mapping_targets('original.Owner -> a.b:\n'+rows, [method])[0]
                self.assertEqual([], target['mappedNames'])

    def test_unknown_metadata_and_unparsed_method_mapping_fail_closed(self):
        method = dict(owner='original.Owner', name='getId', returns='int', parameters=[])
        for extra in ('    # {"id":"com.android.tools.r8.residualsignature","signature":"()I"}\n',
                      '    unexpected method mapping() -> c\n'):
            with self.subTest(extra=extra):
                target = audit.mapping_targets('original.Owner -> a.b:\n    1:1:int getId():1:1 -> c\n'+extra, [method])[0]
                self.assertEqual([], target['mappedNames'])

    def test_duplicate_mapping_owner_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate original'):
            audit.mapping_targets('A -> a:\nA -> b:\n', [])

    def test_old_mapping_cannot_be_replaced_with_current_bytes(self):
        original = next(r for r in audit.CONTRACT['index'] if r['path']=='mapping/release/mapping.txt')
        self.assertEqual('00ecdd068b90a38e340c371fe2d64c159418e277dcf14dc9e9567c9e142b03b2', original['rawSha256'])
        self.assertNotEqual(original['rawSha256'], hashlib.sha256(b'current mapping').hexdigest())
        self.assertEqual(81674789, original['bytes'])

    def test_private_archive_crc_identity_and_deleted_api_stop_before_tools(self):
        method = audit.CONTRACT['methods'][0]
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); archive=root/'runtime.zip'
            rows=[('apks/app-release.apk',b'original apk fixture'),('mapping/release/mapping.txt',b'original fixed mapping'),
                  ('mapping/release/usage.txt',(method['owner']+'\n').encode()),
                  ('BUILD_INFO.json',json.dumps(dict(source=audit.CONTRACT['request']['headSHA'],tree=audit.CONTRACT['sourceTree'],
                      run_number=149,run_attempt=1,version='0.1.0-beta.23',version_code=23,signer_sha256=audit.CONTRACT['signerSha256'],
                      apk_sha256=hashlib.sha256(b'original apk fixture').hexdigest())).encode()),
                  ('source-manifest.json',json.dumps(dict(sourceRevision=audit.CONTRACT['request']['headSHA'],sourceTree=audit.CONTRACT['sourceTree'],
                      workflowRun='40627739662',runNumber=149,runAttempt=1,abi='x86_64',prebuiltApks=True,apkInspectorOutcome='success')).encode())]
            with zipfile.ZipFile(archive,'w') as z:
                for name,raw in rows:z.writestr(name,raw)
            fixed=copy.deepcopy(audit.CONTRACT)
            fixed['request'].update(expectedZIPsha=hashlib.sha256(archive.read_bytes()).hexdigest(),expectedZIPbytes=archive.stat().st_size)
            with zipfile.ZipFile(archive) as z:
                fixed['index']=[dict(path=e.filename,bytes=e.file_size,crc32=f'{e.CRC:08x}',rawSha256=hashlib.sha256(z.read(e)).hexdigest()) for e in z.infolist()]
            private=root/'private';private.mkdir()
            with mock.patch.object(audit,'CONTRACT',fixed),mock.patch.object(audit,'tool_identity') as tools:
                result=audit.audit_archive(archive,private,time.monotonic()+5)
                tools.assert_not_called()
            self.assertEqual('REQUIRED_API_EXPLICITLY_REMOVED',result['status'])
            self.assertFalse(result['compileAuthorized']);self.assertFalse(result['appApkBuilt'])
            bad=copy.deepcopy(fixed);bad['index'][1]['rawSha256']='0'*64
            private2=root/'private2';private2.mkdir()
            with mock.patch.object(audit,'CONTRACT',bad),self.assertRaisesRegex(ValueError,'member closure'):
                audit.audit_archive(archive,private2,time.monotonic()+5)

    def test_workflow_keeps_existing_guards_and_limits(self):
        workflow=(Path(__file__).parents[1]/'.github/workflows/aerove-artifact-evidence.yaml').read_text()
        self.assertIn("github.ref == 'refs/heads/codex/aerove-evidence-reader-34'",workflow)
        self.assertIn('timeout-minutes: 5',workflow)
        self.assertIn('nice -n 19 bash',workflow)
        self.assertIn('if: ${{ !inputs.lite149_api_audit }}',workflow)
        self.assertIn('if: ${{ inputs.lite149_api_audit }}',workflow)
        self.assertNotIn('setup-android',workflow);self.assertNotIn('gradle',workflow)
        self.assertEqual(8*1024*1024,audit.reader.MAX_OUTPUT)


if __name__ == '__main__':
    unittest.main()
