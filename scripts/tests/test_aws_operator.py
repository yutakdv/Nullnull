"""Offline regression tests for the AWS review findings; no AWS calls."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from unittest.mock import patch
import test_aws_staging_scripts as fixtures
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('nullnull_aws_operator',ROOT/'scripts/aws/staging_operator.py')
ops=importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)

class ValidatorRegressions(unittest.TestCase):
    def invoke(self,script,value,*args):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';p.write_text(json.dumps(value))
            return subprocess.run(['node',str(ROOT/'scripts/aws'/script),str(p),*args],capture_output=True,text=True)
    def test_extra_unrestricted_allow_is_rejected(self):
        statement={'Effect':'Allow','Principal':{'Federated':'arn:aws:iam::'+'1'*12+':oidc-provider/token.actions.githubusercontent.com'},'Action':'sts:AssumeRoleWithWebIdentity','Condition':{'StringEquals':{'token.actions.githubusercontent.com:sub':'repo:yutakdv/Nullnull:environment:staging','token.actions.githubusercontent.com:aud':'sts.amazonaws.com'}}}
        extra={**statement,'Action':['sts:AssumeRoleWithWebIdentity']};extra.pop('Condition')
        self.assertNotEqual(0,self.invoke('validate-oidc-trust.mjs',{'Statement':[statement,extra]}).returncode)
    def test_invalid_manifest_types_and_extra_fields_rejected(self):
        for key,value in [('apiImageDigest',['sha256:'+'a'*64]),('flywayChecksums',[None]),('unknown',True)]:
            with self.subTest(key=key):
                manifest=fixtures.ReleaseManifestValidatorTest().valid_manifest();manifest[key]=value
                self.assertNotEqual(0,self.invoke('validate-release-manifest.mjs',manifest).returncode)
    def test_html_health_rejected_and_degraded_json_allowed(self):
        self.assertNotEqual(0,self.invoke('validate-health.mjs',{'status':'READY'},'ready','text/html').returncode)
        self.assertEqual(0,self.invoke('validate-health.mjs',{'status':'DEGRADED','checks':[{'name':'database','status':'READY'}]},'ready','application/json').returncode)
    def test_standalone_migration_cannot_bypass_lock(self):
        result=subprocess.run(['bash',str(ROOT/'scripts/aws/staging-migrate.sh'),'--execute'],capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('locked-deploy-plan',result.stderr)

class OperatorRegressions(unittest.TestCase):
    def test_tree_digest_rejects_symlinks_and_detects_change(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);p=root/'asset';p.write_text('first');before=ops.tree_digest(root)
            p.write_text('changed');self.assertNotEqual(before,ops.tree_digest(root))
            (root/'link').symlink_to(p)
            with self.assertRaisesRegex(ops.OpsError,'symlink'):ops.tree_digest(root)
    def test_profile_clears_ambient_credentials(self):
        with patch.dict(os.environ,{'AWS_ACCESS_KEY_ID':'synthetic','AWS_SESSION_TOKEN':'synthetic','AWS_PROFILE':'chosen'}):
            env=ops.child_env();self.assertNotIn('AWS_ACCESS_KEY_ID',env);self.assertEqual('chosen',env['AWS_PROFILE'])
    def test_lock_is_conditional_and_not_released_on_unknown_result(self):
        with patch.object(ops,'aws',return_value={}) as aws:
            with self.assertRaises(ops.OpsError):
                # An AWS write was started and its outcome is unknown: the lock must stay.
                with ops.DeploymentLock() as lock:lock.mutating();raise ops.OpsError('unknown')
            self.assertEqual(1,aws.call_count)
            self.assertEqual('attribute_not_exists(LockId)',aws.call_args.kwargs['ConditionExpression'])
    def test_lock_release_requires_matching_owner(self):
        with patch.object(ops,'aws',return_value={}) as aws:
            with ops.DeploymentLock():pass
            self.assertEqual('delete-item',aws.call_args.args[1])
            self.assertIn('ConditionExpression',aws.call_args.kwargs)
    PROFILE={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p'}
    def run_execute(self, action, kind, live_change=None, deployed=None, target=None, accept=False, classification=True,
                    stale_baseline=False, env=None, source_state='clean', fail_on=None, cli_writes=False,
                    preserve_open_edge=False, live_edge='true', health_statuses=(200,200), missing_webedge=False,
                    planned_web_cors=None):
        """execute() with AWS replaced at its edges only: manifest validation (the real node validator), the live
        classification and the lock logic run for real. Returns (cdk mock, migration mock); self.aws_ops lists
        the lock's AWS operations."""
        import argparse
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);plan=directory/'plan.json';plan.write_text('{}');assembly=directory/'assembly';assembly.mkdir()
            templates={s:{'Resources':{'R':{'Type':'AWS::SNS::Topic','Properties':{'TopicName':s}}}} for s in ops.STACKS}
            for s,t in templates.items():(assembly/f'NullnullStg{s}.template.json').write_text(json.dumps(t))
            live=json.loads(json.dumps(templates))
            if live_change:live[live_change]['Resources']['R']['Properties']['TopicName']='changed'
            if missing_webedge:live['WebEdge']=None
            if planned_web_cors is not None:
                templates['WebEdge']['Resources']['WebBucket']={
                    'Type':'AWS::S3::Bucket','Properties':{'CorsConfiguration':planned_web_cors}}
                live['WebEdge']['Resources']['WebBucket']={'Type':'AWS::S3::Bucket','Properties':{}}
                (assembly/'NullnullStgWebEdge.template.json').write_text(json.dumps(templates['WebEdge']))
            manifest={**fixtures.ReleaseManifestValidatorTest().valid_manifest(),'flywayChecksums':target or ['V001:'+'a'*64],'sourceState':source_state}
            if source_state=='overlay':manifest.update(sourceOverlaySha256='sha256:'+'e'*64,sourceOverlayPaths=['apps/api/x.java'])
            (directory/'release.json').write_text(json.dumps(manifest))
            data={'action':action,'account':'1'*12,'assemblySha256':ops.tree_digest(assembly),'verifierTokenSha256':'f'*64,'acceptNewerSchema':accept}
            if classification:
                (directory/'classification.json').write_text(json.dumps({'baselineSha256':ops.baseline_sha256(templates if stale_baseline else live)}))
            record={'releaseManifest':{'flywayChecksums':deployed or ['V001:'+'a'*64]}}
            self.aws_ops=[]
            health=iter(health_statuses)
            def fake_aws(service, operation, **kw):
                self.aws_ops.append(operation);return {}
            def fake_cdk(command, log=None):
                if command[1]==fail_on:raise ops.OpsError('command-failed-cdk')
                if cli_writes:
                    # What the real CLI did on 2026-09-19: zip directory assets into <app>/.cache/.
                    app=Path(command[command.index('--app')+1]);(app/'.cache').mkdir(exist_ok=True)
                    (app/'.cache'/(command[1]+'.zip')).write_text('zip')
            with patch.dict(os.environ,env or self.PROFILE),patch.object(ops,'verify_plan',return_value=data),patch.object(ops,'identity'),patch.object(ops,'verify_images'),patch.object(ops,'require_kto_secret_provisioned'),patch.object(ops,'read_current_release',return_value=record),patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'live_bodies',return_value=live),patch.object(ops,'protected_templates',return_value={}),patch.object(ops,'guard_stateful'),patch.object(ops,'output',return_value='synthetic'),patch.object(ops,'aws',side_effect=fake_aws),patch.object(ops.DeploymentLock,'check'),patch.object(ops,'record_release') as release_record,patch.object(ops,'migration') as migration,patch.object(ops,'cdk',side_effect=fake_cdk) as cdk,patch.object(ops,'edge_traffic_enabled',return_value=live_edge),patch.object(ops,'public_health_answers',side_effect=lambda *a,**kw: iter([(next(health),'application/json',None)])):
                self.release_record=release_record
                ops.execute(argparse.Namespace(plan=str(plan),approved_plan_sha256='synthetic',action=action,kind=kind,
                                                    preserve_open_edge=preserve_open_edge))
                self.approved_assembly_untouched=ops.tree_digest(assembly)==data['assemblySha256']
                return cdk, migration
    def deployed(self, cdk):
        return [c.args[0][1] for c in cdk.call_args_list]
    def test_rollback_never_migrates_or_deploys_protected_stacks(self):
        cdk, migration = self.run_execute('rollback', 'app')
        migration.assert_not_called()
        self.assertEqual(['NullnullStgMigration','NullnullStgWebEdge','NullnullStgServices'],self.deployed(cdk))
        self.assertTrue(all('--exclusively' in c.args[0] for c in cdk.call_args_list))
    def test_ci_rollback_accepts_a_recorded_release_of_another_commit(self):
        # F2: the deploy job exports this run's SHA; the recorded release is older by definition.
        env={**AuthModeRegressions.AMBIENT,'NULLNULL_EXPECTED_GIT_SHA':'d'*40}
        cdk,_=self.run_execute('rollback','app',env=env)
        self.assertEqual(3,cdk.call_count)
    def test_rollback_that_undoes_a_template_change_needs_the_infra_path(self):
        with self.assertRaisesRegex(ops.OpsError,'rollback-requires-infra-approval'):
            self.run_execute('rollback','app',live_change='Services')
        self.assertIn('delete-item',self.aws_ops)
        cdk,_=self.run_execute('rollback','infra',live_change='Services')
        self.assertEqual(['NullnullStgMigration','NullnullStgWebEdge','NullnullStgServices'],self.deployed(cdk))
    def test_rollback_ignores_protected_stacks_it_never_deploys(self):
        cdk,_=self.run_execute('rollback','app',live_change='Data')
        self.assertEqual(3,cdk.call_count)
    def test_rollback_to_an_overlay_release_needs_the_infra_path(self):
        with self.assertRaisesRegex(ops.OpsError,'rollback-requires-infra-approval'):
            self.run_execute('rollback','app',source_state='overlay')
    def test_app_release_never_deploys_protected_stacks_and_keeps_edge_closed(self):
        cdk, migration = self.run_execute('deploy', 'app')
        migration.assert_called_once()
        self.assertEqual(['NullnullStgMigration','NullnullStgWebEdge','NullnullStgServices'],[c.args[0][1] for c in cdk.call_args_list])
        web=[c.args[0] for c in cdk.call_args_list if c.args[0][1]=='NullnullStgWebEdge'][0]
        self.assertIn('NullnullStgWebEdge:TrafficEnabled=false', web)
        self.assertIn('NullnullStgWebEdge:VerifierTokenSha256='+'f'*64, web)
        self.assertTrue(all('--toolkit-stack-name' in c.args[0] for c in cdk.call_args_list))
    def test_reviewed_migration_only_release_can_preserve_an_already_open_edge(self):
        cdk,migration=self.run_execute('deploy','infra',live_change='Migration',preserve_open_edge=True)
        migration.assert_called_once()
        web=[c.args[0] for c in cdk.call_args_list if c.args[0][1]=='NullnullStgWebEdge'][0]
        self.assertIn('NullnullStgWebEdge:TrafficEnabled=true',web)
        self.assertLess(self.deployed(cdk).index('NullnullStgWebEdge'),self.deployed(cdk).index('NullnullStgMigration'))
        self.release_record.assert_called_once()
        self.assertTrue(self.approved_assembly_untouched)
    def test_reviewed_exact_upload_cors_can_preserve_an_already_open_edge(self):
        cors={'CorsRules':[{'AllowedOrigins':['https://d54awmnmi4c3z.cloudfront.net'],
                            'AllowedMethods':['PUT'],'AllowedHeaders':['content-type'],'MaxAge':300}]}
        cdk,_=self.run_execute('deploy','infra',planned_web_cors=cors,preserve_open_edge=True)
        self.assertIn('NullnullStgWebEdge:TrafficEnabled=true',
                      [c.args[0] for c in cdk.call_args_list if c.args[0][1]=='NullnullStgWebEdge'][0])
        self.assertTrue(self.approved_assembly_untouched)
        for bad in [{**cors,'CorsRules':[{**cors['CorsRules'][0],'AllowedOrigins':['*']}]},
                    {**cors,'CorsRules':[{**cors['CorsRules'][0],'AllowedMethods':['GET','PUT']}]}]:
            with self.subTest(bad=bad),self.assertRaisesRegex(ops.OpsError,'preserve-open-template-change'):
                self.run_execute('deploy','infra',planned_web_cors=bad,preserve_open_edge=True)
            self.assertEqual(['put-item','delete-item'],self.aws_ops)
        with self.assertRaisesRegex(ops.OpsError,'preserve-open-template-change'):
            self.run_execute('deploy','infra',planned_web_cors=cors,live_change='WebEdge',preserve_open_edge=True)
        self.assertEqual(['put-item','delete-item'],self.aws_ops)
    def test_open_edge_release_refuses_closed_edge_or_other_template_drift_before_any_write(self):
        for kwargs,reason in [({'live_edge':'false'},'preserve-open-requires-open-edge'),
                              ({'live_change':'WebEdge'},'preserve-open-template-change'),
                              ({'live_change':'Services'},'preserve-open-template-change')]:
            with self.subTest(kwargs=kwargs),self.assertRaisesRegex(ops.OpsError,reason):
                self.run_execute('deploy','infra',preserve_open_edge=True,**kwargs)
            self.assertEqual(['put-item','delete-item'],self.aws_ops)
    def test_open_edge_mode_is_for_deploy_not_rollback(self):
        with self.assertRaisesRegex(ops.OpsError,'preserve-open-deploy-only'):
            self.run_execute('rollback','infra',preserve_open_edge=True)
    def test_open_edge_mode_requires_unchanged_schema_and_healthy_public_endpoint(self):
        with self.assertRaisesRegex(ops.OpsError,'preserve-open-schema-change'):
            self.run_execute('deploy','infra',preserve_open_edge=True,target=['V002:'+'b'*64])
        self.assertEqual(['put-item','delete-item'],self.aws_ops)
        with self.assertRaisesRegex(ops.OpsError,'public-edge-unhealthy-before-deploy'):
            self.run_execute('deploy','infra',preserve_open_edge=True,health_statuses=(503,))
        self.assertEqual(['put-item','delete-item'],self.aws_ops)
        with self.assertRaisesRegex(ops.OpsError,'public-edge-unhealthy-after-deploy'):
            self.run_execute('deploy','infra',preserve_open_edge=True,health_statuses=(200,503))
        self.release_record.assert_not_called()
        self.assertEqual(['put-item'],self.aws_ops)
    def test_app_release_with_infra_drift_is_refused_before_any_deploy(self):
        with self.assertRaisesRegex(ops.OpsError,'infra-change-requires-infra-approval'):
            self.run_execute('deploy', 'app', live_change='Data')
    def test_a_check_that_fails_before_any_write_releases_the_lock(self):
        with self.assertRaises(ops.OpsError):self.run_execute('deploy','app',live_change='Data')
        self.assertEqual(['put-item','delete-item'],self.aws_ops)
    def test_a_failure_after_a_write_keeps_the_lock(self):
        with self.assertRaisesRegex(ops.OpsError,'command-failed-cdk'):
            self.run_execute('deploy','app',fail_on='NullnullStgServices')
        self.assertEqual(['put-item'],self.aws_ops)
    def test_infra_release_deploys_protected_in_order_then_migrates(self):
        cdk, migration = self.run_execute('deploy', 'infra')
        self.assertEqual(['NullnullStg'+n for n in ['Foundation','Network','GlobalWaf','Data','Platform','Observability','Migration','WebEdge','Services']],
                         self.deployed(cdk))
        migration.assert_called_once()
    def test_first_infra_deploy_creates_webedge_exports_before_migration_imports_them(self):
        cdk,migration=self.run_execute('deploy','infra',missing_webedge=True)
        deployed=self.deployed(cdk)
        self.assertLess(deployed.index('NullnullStgWebEdge'),deployed.index('NullnullStgMigration'))
        web=[c.args[0] for c in cdk.call_args_list if c.args[0][1]=='NullnullStgWebEdge'][0]
        self.assertIn('NullnullStgWebEdge:TrafficEnabled=false',web)
    def test_the_cli_deploys_a_copy_so_its_writes_never_touch_the_approved_assembly(self):
        cdk,_=self.run_execute('deploy','infra',cli_writes=True)
        self.assertEqual(9,cdk.call_count)
        self.assertTrue(self.approved_assembly_untouched)
    def test_infra_execute_requires_the_reviewed_classification(self):
        with self.assertRaisesRegex(ops.OpsError,'infra-execute-requires-classification'):
            self.run_execute('deploy','infra',classification=False)
    def test_any_live_change_after_classification_voids_the_infra_approval(self):
        with self.assertRaisesRegex(ops.OpsError,'live-stacks-changed-since-classification'):
            self.run_execute('deploy','infra',live_change='Services',stale_baseline=True)
    def test_rollback_onto_a_newer_schema_needs_the_recorded_decision(self):
        newer=['V001:'+'a'*64,'V002:'+'b'*64]
        with self.assertRaisesRegex(ops.OpsError,'requires-accept-newer-schema'):
            self.run_execute('rollback', 'app', deployed=newer, target=newer[:1])
        # Accepting the newer schema is itself a reviewed decision: it takes the infra path.
        with self.assertRaisesRegex(ops.OpsError,'rollback-requires-infra-approval'):
            self.run_execute('rollback', 'app', deployed=newer, target=newer[:1], accept=True)
        cdk,_=self.run_execute('rollback', 'infra', deployed=newer, target=newer[:1], accept=True)
        self.assertEqual(3, cdk.call_count)
    def test_rollback_to_a_diverging_schema_is_always_refused(self):
        with self.assertRaisesRegex(ops.OpsError,'rollback-target-schema-diverges'):
            self.run_execute('rollback', 'infra', deployed=['V001:'+'a'*64], target=['V001:'+'c'*64], accept=True)
    def test_execute_requires_explicit_kind(self):
        for action in ['deploy','rollback']:
            with self.subTest(action=action),self.assertRaisesRegex(ops.OpsError,'execute-requires-kind'):
                self.run_execute(action, None)
    def test_manifest_image_mismatch_blocks_run_task(self):
        from types import SimpleNamespace
        task={'taskDefinition':{'containerDefinitions':[{'name':'migration','image':'repo@sha256:'+'b'*64}]}}
        with patch.object(ops,'output',return_value='synthetic'),patch.object(ops,'aws',return_value=task) as aws:
            with self.assertRaisesRegex(ops.OpsError,'image-mismatch'):
                ops.migration({'apiImageDigest':'sha256:'+'a'*64},SimpleNamespace(check=lambda:None))
            self.assertEqual(1,aws.call_count)

    def test_every_manifest_bound_artifact_is_compared_before_it_is_used(self):
        # BA-071-T3 (A-047): the deployed set is the manifest's whole set, not only its image digests.
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); (root/'docs/api').mkdir(parents=True); (root/'docs/contracts').mkdir(parents=True)
            (root/'apps/api/src/main/resources/db/migration').mkdir(parents=True); web=root/'web'; web.mkdir()
            (root/'docs/api/openapi.yaml').write_text('openapi')
            (root/'docs/contracts/events.schema.json').write_text('{}')
            (root/'apps/api/src/main/resources/db/migration/V001__a.sql').write_text('select 1;')
            (web/'index.html').write_text('<p>')
            with patch.object(ops,'ROOT',root):
                good={'openApiSha256':'sha256:'+ops.digest(root/'docs/api/openapi.yaml'),
                      'eventSchemaSha256':'sha256:'+ops.digest(root/'docs/contracts/events.schema.json'),
                      'webArtifactSha256':'sha256:'+ops.tree_digest(web),
                      'flywayChecksums':['V001__a.sql:'+ops.digest(root/'apps/api/src/main/resources/db/migration/V001__a.sql')]}
                ops.check_artifacts(good,web)
                for field,bad,reason in [('openApiSha256','sha256:'+'0'*64,'artifact-mismatch-openApiSha256'),
                                         ('eventSchemaSha256','sha256:'+'0'*64,'artifact-mismatch-eventSchemaSha256'),
                                         ('webArtifactSha256','sha256:'+'0'*64,'web-artifact-mismatch'),
                                         ('flywayChecksums',[],'migration-checksum-mismatch')]:
                    with self.subTest(field=field),self.assertRaisesRegex(ops.OpsError,reason):
                        ops.check_artifacts({**good,field:bad},web)

    def test_stateful_property_change_is_blocked(self):
        old={'Resources':{'Db':{'Type':'AWS::RDS::DBInstance','Properties':{'DBInstanceClass':'db.t4g.micro'}}}}
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'NullnullStgData.template.json'
            p.write_text(json.dumps({'Resources':{'Db':{'Type':'AWS::RDS::DBInstance','Properties':{'DBInstanceClass':'db.t4g.large'}}}}))
            with patch.object(ops,'aws',return_value={'TemplateBody':old}):
                with self.assertRaisesRegex(ops.OpsError,'stateful-change'):
                    ops.guard_stateful('Data',Path(d))

    def test_stateful_guard_allows_only_the_approved_web_bucket_cors_addition(self):
        cors={'CorsRules':[{'AllowedOrigins':['https://d54awmnmi4c3z.cloudfront.net'],
                            'AllowedMethods':['PUT'],'AllowedHeaders':['content-type'],'MaxAge':300}]}
        old={'Resources':{'WebBucket':{'Type':'AWS::S3::Bucket','Properties':{'VersioningConfiguration':{'Status':'Enabled'}}}}}
        planned=json.loads(json.dumps(old))
        planned['Resources']['WebBucket']['Properties']['CorsConfiguration']=cors
        with tempfile.TemporaryDirectory() as d, patch.object(ops,'aws',return_value={'TemplateBody':old}):
            path=Path(d)/'NullnullStgWebEdge.template.json'
            path.write_text(json.dumps(planned))
            ops.guard_stateful('WebEdge',Path(d))
            for changed in ('origin','versioning','existing-cors'):
                with self.subTest(changed=changed):
                    variant=json.loads(json.dumps(planned))
                    previous=json.loads(json.dumps(old))
                    if changed=='origin':
                        variant['Resources']['WebBucket']['Properties']['CorsConfiguration']['CorsRules'][0]['AllowedOrigins']=['https://example.invalid']
                    elif changed=='versioning':
                        variant['Resources']['WebBucket']['Properties']['VersioningConfiguration']['Status']='Suspended'
                    else:
                        previous['Resources']['WebBucket']['Properties']['CorsConfiguration']={'CorsRules':[]}
                    path.write_text(json.dumps(variant))
                    with patch.object(ops,'aws',return_value={'TemplateBody':previous}):
                        with self.assertRaisesRegex(ops.OpsError,'stateful-change'):
                            ops.guard_stateful('WebEdge',Path(d))
            (Path(d)/'NullnullStgData.template.json').write_text(json.dumps(planned))
            with self.assertRaisesRegex(ops.OpsError,'stateful-change'):
                ops.guard_stateful('Data',Path(d))

    def guard(self, old_tags, new_tags, stack='WebEdge'):
        def bucket(tags): return {'Resources':{'WebBucket':{'Type':'AWS::S3::Bucket','Properties':{'Tags':tags}}}}
        with tempfile.TemporaryDirectory() as d:
            (Path(d)/f'NullnullStg{stack}.template.json').write_text(json.dumps(bucket(new_tags)))
            with patch.object(ops,'aws',return_value={'TemplateBody':bucket(old_tags)}):
                ops.guard_stateful(stack,Path(d))

    def test_a_cdk_ownership_tag_added_by_a_new_bucket_deployment_passes(self):
        # Run 35461072422: the covers BucketDeployment added aws-cdk:cr-owned:covers/:a11792fd to the web bucket.
        base=[{'Key':'aws-cdk:cr-owned:89f9b8d0','Value':'true'},{'Key':'Project','Value':'Nullnull'}]
        self.guard(base, base[:1]+[{'Key':'aws-cdk:cr-owned:covers/:a11792fd','Value':'true'}]+base[1:])

    def test_any_other_tag_change_on_a_stateful_resource_is_still_blocked(self):
        base=[{'Key':'Project','Value':'Nullnull'}]
        for new in ([{'Key':'Project','Value':'Other'}], base+[{'Key':'Owner','Value':'x'}], [],
                    base+[{'Key':'aws-cdk:cr-owned','Value':'true'}]):
            with self.subTest(new=new),self.assertRaisesRegex(ops.OpsError,'stateful-change'):
                self.guard(base,new)


class AuthModeRegressions(unittest.TestCase):
    AMBIENT={'NULLNULL_AWS_AUTH':'ambient','GITHUB_ACTIONS':'true','AWS_ACCESS_KEY_ID':'ASIASYNTHETIC',
             'AWS_SECRET_ACCESS_KEY':'synthetic','AWS_SESSION_TOKEN':'synthetic','AWS_PROFILE':'stray'}
    def test_ambient_keeps_session_credentials_and_drops_profiles(self):
        with patch.dict(os.environ,self.AMBIENT):
            env=ops.child_env()
            self.assertEqual('ASIASYNTHETIC',env['AWS_ACCESS_KEY_ID']);self.assertNotIn('AWS_PROFILE',env)
            self.assertNotIn('--profile',ops.aws_base())
    def test_ambient_outside_github_actions_or_without_session_is_refused(self):
        for drop in ['GITHUB_ACTIONS','AWS_SESSION_TOKEN']:
            with self.subTest(drop=drop):
                env={k:v for k,v in self.AMBIENT.items() if k!=drop}
                with patch.dict(os.environ,env,clear=True):
                    with self.assertRaises(ops.OpsError):ops.child_env()
    def test_profile_mode_passes_the_profile(self):
        with patch.dict(os.environ,{'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'nullnull-staging'}):
            self.assertEqual(['--profile','nullnull-staging'],ops.aws_base()[4:6])
    def test_unknown_mode_is_refused(self):
        with patch.dict(os.environ,{'NULLNULL_AWS_AUTH':'sso'}):
            with self.assertRaisesRegex(ops.OpsError,'invalid-NULLNULL_AWS_AUTH'):ops.child_env()
    def test_identity_requires_the_expected_role_for_the_mode(self):
        account='1'*12
        cases=[('profile',f'arn:aws:sts::{account}:assumed-role/nullnull-stg-operator/s',True),
               ('profile',f'arn:aws:iam::{account}:user/Yutak_trading',False),
               ('profile',f'arn:aws:sts::{account}:assumed-role/nullnull-stg-github-deploy/s',False),
               ('ambient',f'arn:aws:sts::{account}:assumed-role/nullnull-stg-github-deploy/s',True)]
        for mode,arn,ok in cases:
            with self.subTest(mode=mode,arn=arn),patch.dict(os.environ,{'NULLNULL_AWS_AUTH':mode}),patch.object(ops,'aws',return_value={'Account':account,'Arn':arn}):
                if ok: ops.identity(account)
                else:
                    with self.assertRaisesRegex(ops.OpsError,'unexpected-aws-principal'):ops.identity(account)
    def test_ci_requires_expected_sha_and_clean_source(self):
        manifest=fixtures.ReleaseManifestValidatorTest().valid_manifest();manifest['sourceState']='clean'
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'release.json';path.write_text(json.dumps(manifest))
            with patch.dict(os.environ,{**self.AMBIENT,'NULLNULL_EXPECTED_GIT_SHA':'b'*40}):
                self.assertEqual('clean',ops.validate_manifest(path)['sourceState'])
            with patch.dict(os.environ,self.AMBIENT):
                os.environ.pop('NULLNULL_EXPECTED_GIT_SHA',None)
                with self.assertRaisesRegex(ops.OpsError,'ci-requires-expected-git-sha'):ops.validate_manifest(path)
            overlay={**manifest,'sourceState':'overlay','sourceOverlaySha256':'sha256:'+'e'*64,'sourceOverlayPaths':['x.java']};path.write_text(json.dumps(overlay))
            with patch.dict(os.environ,{**self.AMBIENT,'NULLNULL_EXPECTED_GIT_SHA':'b'*40}):
                with self.assertRaisesRegex(ops.OpsError,'ci-requires-clean-source'):ops.validate_manifest(path)
    def test_a_recorded_release_is_validated_without_this_runs_sha(self):
        # No patches: the real node validator runs with the deploy job's environment (F2).
        manifest=fixtures.ReleaseManifestValidatorTest().valid_manifest()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'release.json';path.write_text(json.dumps(manifest))
            with patch.dict(os.environ,{**self.AMBIENT,'NULLNULL_EXPECTED_GIT_SHA':'d'*40}):
                self.assertEqual('b'*40,ops.validate_manifest(path,recorded=True)['gitSha'])
                with self.assertRaisesRegex(ops.OpsError,'command-failed-node'):ops.validate_manifest(path)

class ClassificationRegressions(unittest.TestCase):
    # 'web' stands for the asset hash CDK derives from the bundle - unrelated to the release identity.
    OLD={'apiImageDigest':'sha256:'+'1'*64,'releaseVersion':'v0.1.0-rc.1','web':'e'*64}
    NEW={'apiImageDigest':'sha256:'+'3'*64,'releaseVersion':'v0.1.0-rc.2','web':'f'*64}
    def services(self, release, public='false', note=None):
        # The shape CDK synthesizes: the digest is the last Fn::Join part of the ECR image URI.
        image={'Fn::Join':['',[{'Fn::Select':[4,{'Fn::Split':[':',{'Fn::ImportValue':'repo-arn'}]}]},'.dkr.ecr.',{'Ref':'AWS::URLSuffix'},'/',
                               {'Fn::ImportValue':'repo-name'},'@'+release['apiImageDigest']]]}
        env=[{'Name':'APP_RELEASE_VERSION','Value':release['releaseVersion']},{'Name':'NULLNULL_CATALOG_PUBLIC_ENABLED','Value':public}]
        if note:env.append({'Name':'NOTE','Value':note})
        return {'Resources':{'CDKMetadata':{'Type':'AWS::CDK::Metadata','Properties':{'Analytics':'v2:deflate64:x'}},
            'Task':{'Type':'AWS::ECS::TaskDefinition','Metadata':{'aws:cdk:path':'x'},'Properties':{'ContainerDefinitions':[{'Image':image,'Environment':env}]}},
            'Web':{'Type':'Custom::CDKBucketDeployment','Properties':{'SourceObjectKeys':[release['web']+'.zip'],'DistributionPaths':['/*']}}},
            'Parameters':{'BootstrapVersion':{'Type':'String'}},'Rules':{'CheckBootstrapVersion':{}}}
    def test_release_identity_and_web_bundle_are_the_app_path(self):
        self.assertEqual(ops.normalize_template(self.services(self.OLD)),ops.normalize_template(self.services(self.NEW)))
    def test_any_other_change_is_infra(self):
        self.assertNotEqual(ops.normalize_template(self.services(self.OLD)),ops.normalize_template(self.services(self.NEW,'true')))
    def test_only_the_release_markers_are_masked_not_look_alikes(self):
        # A digest or version string anywhere else is a real template change.
        for old,new in [('@sha256:'+'1'*64,'@sha256:'+'2'*64),('v0.1.0-rc.1','v0.1.0-rc.2')]:
            with self.subTest(old=old):
                self.assertNotEqual(ops.normalize_template(self.services(self.OLD,note=old)),ops.normalize_template(self.services(self.OLD,note=new)))
    def test_live_template_string_body_is_accepted(self):
        self.assertEqual(ops.normalize_template(json.dumps(self.services(self.OLD))),ops.normalize_template(self.services(self.OLD)))
    def test_the_infra_baseline_sees_digest_only_changes(self):
        # Normalized equal (app path) yet a different baseline: an app release since classification voids it.
        self.assertNotEqual(ops.baseline_sha256({'Services':self.services(self.OLD)}),ops.baseline_sha256({'Services':self.services(self.NEW)}))
    def assembly(self, directory, template):
        assembly=Path(directory)/'assembly';assembly.mkdir()
        for stack in ops.STACKS:(assembly/f'NullnullStg{stack}.template.json').write_text(json.dumps(template))
    def test_missing_previous_release_record_is_infra_and_still_diffed(self):
        with tempfile.TemporaryDirectory() as d:
            self.assembly(d,{'Resources':{}})
            live={s:None for s in ops.STACKS};live['Foundation']={'Resources':{}}
            with patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=None):
                findings=ops.classify_findings(Path(d),{'flywayChecksums':['V001:'+'c'*64]},live)
        self.assertEqual(['no-previous-release-record','migration-set-changed'],findings[:2])
        self.assertIn('stack-missing-Data',findings);self.assertNotIn('template-changed-Foundation',findings)
    def test_migration_set_change_is_infra_even_with_identical_templates(self):
        with tempfile.TemporaryDirectory() as d:
            self.assembly(d,{'Resources':{}})
            live={s:{'Resources':{}} for s in ops.STACKS}
            previous={'releaseManifest':{**self.OLD,'flywayChecksums':['V001__a.sql:'+'c'*64]}}
            with patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=previous):
                self.assertEqual([],ops.classify_findings(Path(d),{**self.NEW,'flywayChecksums':['V001__a.sql:'+'c'*64]},live))
                self.assertEqual(['migration-set-changed'],ops.classify_findings(Path(d),{**self.NEW,'flywayChecksums':['V001__a.sql:'+'c'*64,'V002__b.sql:'+'d'*64]},live))
    def test_the_reviewer_diff_shows_the_change_and_never_an_account_id(self):
        account='123456789012'
        with tempfile.TemporaryDirectory() as d:
            self.assembly(d,{'Resources':{'Role':{'Type':'AWS::IAM::Role','Properties':{'Arn':f'arn:aws:iam::{account}:policy/Boundary','Max':2}}}})
            live={s:{'Resources':{'Role':{'Type':'AWS::IAM::Role','Properties':{'Arn':f'arn:aws:iam::{account}:policy/Boundary','Max':1}}}} for s in ops.STACKS}
            diff=ops.template_diff(Path(d),live,['template-changed-Services'],{'flywayChecksums':['V001:'+'a'*64]},
                                   {'flywayChecksums':['V001:'+'a'*64,'V002:'+'b'*64]},account)
        self.assertIn('migration added: V002',diff)
        self.assertIn('+++ plan/NullnullStgServices',diff);self.assertRegex(diff,r'(?m)^\+\s+"Max": 2$')
        self.assertNotIn(account,diff);self.assertNotIn('NullnullStgData',diff)

class RollbackClassificationRegressions(unittest.TestCase):
    def findings(self, manifest, data, change=None):
        with tempfile.TemporaryDirectory() as d:
            assembly=Path(d)/'assembly';assembly.mkdir()
            for stack in ops.STACKS:(assembly/f'NullnullStg{stack}.template.json').write_text(json.dumps({'Resources':{'R':{'Type':'X','Properties':{'N':stack}}}}))
            live={s:{'Resources':{'R':{'Type':'X','Properties':{'N':s}}}} for s in ops.STACKS}
            if change:live[change]['Resources']['R']['Properties']['N']='changed'
            return ops.rollback_findings(Path(d),manifest,data,live)
    def test_a_clean_release_with_only_its_own_markers_is_an_app_rollback(self):
        self.assertEqual([],self.findings({'sourceState':'clean'},{}))
        self.assertEqual([],self.findings({'sourceState':'clean'},{},change='Data'))
    def test_overlay_newer_schema_or_app_stack_drift_is_reviewed(self):
        self.assertEqual(['rollback-target-is-not-a-clean-build'],self.findings({'sourceState':'overlay'},{}))
        self.assertEqual(['rollback-accepts-newer-schema'],self.findings({'sourceState':'clean'},{'acceptNewerSchema':True}))
        self.assertEqual(['template-changed-WebEdge'],self.findings({'sourceState':'clean'},{},change='WebEdge'))

class AwsParameterNameRegressions(unittest.TestCase):
    """`--cli-input-json` keys must be the API's own names. ECS/ECR/Logs use camelCase and CloudFormation/DynamoDB/
    Secrets Manager/IAM use PascalCase. `TaskDefinition=` for ecs:DescribeTaskDefinition stopped the first real deploy
    (2026-09-19) because every test patched aws(); checked here without the CLI."""
    CASE = {'ecs': str.islower, 'ecr': str.islower, 'logs': str.islower, 'cloudformation': str.isupper,
            'dynamodb': str.isupper, 'secretsmanager': str.isupper, 'iam': str.isupper, 'sts': str.isupper,
            'rds': str.isupper}
    def test_every_aws_call_uses_the_services_parameter_case(self):
        import ast
        seen = 0
        for script in ['scripts/aws/staging_operator.py', 'scripts/aws/staging-iam.py']:
            for node in ast.walk(ast.parse((ROOT/script).read_text())):
                if not (isinstance(node, ast.Call) and getattr(node.func, 'id', None) == 'aws' and len(node.args) >= 2
                        and isinstance(node.args[0], ast.Constant)):
                    continue
                service = node.args[0].value
                self.assertIn(service, self.CASE, f'{script}: add the parameter case of {service}')
                for keyword in node.keywords:
                    if keyword.arg and keyword.arg != 'region':
                        seen += 1
                        with self.subTest(script=script, line=node.lineno, key=keyword.arg):
                            self.assertTrue(self.CASE[service](keyword.arg[0]))
        self.assertGreater(seen, 40)

class ClassifyCommandRegressions(unittest.TestCase):
    """The workflow routes the reviewer on classify()'s printed kind, so its deploy/rollback branch matters."""
    def run_classify(self, action, drift=None, source_state='clean'):
        import argparse, contextlib, io
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);(directory/'assembly').mkdir();(directory/'plan.json').write_text('{}')
            for s in ops.STACKS:(directory/'assembly'/f'NullnullStg{s}.template.json').write_text(json.dumps({'Resources':{'R':{'Type':'X','Properties':{'N':s}}}}))
            live={s:{'Resources':{'R':{'Type':'X','Properties':{'N':s}}}} for s in ops.STACKS}
            if drift:live[drift]['Resources']['R']['Properties']['N']='changed'
            manifest={**fixtures.ReleaseManifestValidatorTest().valid_manifest(),'flywayChecksums':['V001:'+'a'*64],'sourceState':source_state}
            if source_state=='overlay':manifest.update(sourceOverlaySha256='sha256:'+'e'*64,sourceOverlayPaths=['apps/api/x.java'])
            (directory/'release.json').write_text(json.dumps(manifest))
            record={'releaseManifest':{**manifest,'gitSha':'c'*40,'flywayChecksums':['V001:'+'a'*64]}}
            out=io.StringIO()
            with patch.dict(os.environ,OperatorRegressions.PROFILE),patch.object(ops,'verify_plan',return_value={'action':action,'account':'1'*12}),patch.object(ops,'identity'),patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=record),patch.object(ops,'live_bodies',return_value=live),contextlib.redirect_stdout(out):
                ops.classify(argparse.Namespace(plan=str(directory/'plan.json'),approved_plan_sha256='x'))
            return out.getvalue(), json.loads((directory/'classification.json').read_text())
    def test_a_rollback_is_judged_on_the_stacks_it_deploys(self):
        # Protected-stack drift is irrelevant to a rollback (it never deploys them), but not to a deploy.
        text,recorded=self.run_classify('rollback',drift='Data')
        self.assertIn('release_kind=app',text);self.assertEqual([],recorded['findings'])
        text,recorded=self.run_classify('deploy',drift='Data')
        self.assertIn('release_kind=infra',text);self.assertEqual(['template-changed-Data'],recorded['findings'])
    def test_a_rollback_to_an_overlay_release_is_routed_to_the_reviewer(self):
        text,recorded=self.run_classify('rollback',source_state='overlay')
        self.assertIn('release_kind=infra',text);self.assertIn('rollback-target-is-not-a-clean-build',recorded['findings'])

class VerifierTokenRegressions(unittest.TestCase):
    def test_a_release_plan_without_the_token_is_refused(self):
        with patch.dict(os.environ,{},clear=False):
            os.environ.pop('NULLNULL_VERIFIER_TOKEN',None)
            with self.assertRaisesRegex(ops.OpsError,'verifier-token-required'):ops.verifier_hash()
        with patch.dict(os.environ,{'NULLNULL_VERIFIER_TOKEN':'short'}):
            with self.assertRaisesRegex(ops.OpsError,'weak-or-malformed-verifier-token'):ops.verifier_hash()
    def saved_plan(self, directory, action, verifier):
        import datetime as dt, hashlib
        d=Path(directory);(d/'assembly').mkdir();(d/'release.json').write_text('{}');(d/'cost-basis.txt').write_text('x')
        now=dt.datetime.now(dt.timezone.utc)
        data={'version':1,'region':ops.REGION,'account':'1'*12,'action':action,'createdAt':now.isoformat(),
              'expiresAt':min(now+dt.timedelta(days=1),ops.EXPIRY).isoformat(),'estimateUsd':80,'reserveUsd':20,
              'releaseSha256':ops.digest(d/'release.json'),'assemblySha256':ops.tree_digest(d/'assembly'),
              'costBasisSha256':ops.digest(d/'cost-basis.txt'),'toolchainSha256':ops.digest(ROOT/'infra/package-lock.json'),
              'verifierTokenSha256':verifier}
        (d/'plan.json').write_text(json.dumps(data))
        return d/'plan.json', hashlib.sha256((d/'plan.json').read_bytes()).hexdigest()
    def test_only_the_bootstrap_plan_may_carry_no_verifier_hash(self):
        # The staging window is pinned so this still runs (and means the same) after the real expiry.
        with patch.dict(os.environ,{'NULLNULL_AWS_ACCOUNT_ID':'1'*12}),patch.object(ops,'EXPIRY',ops.dt.datetime(2099,1,1,tzinfo=ops.dt.timezone.utc)):
            for action,verifier,ok in [('bootstrap','',True),('deploy','',False),('rollback','',False),('deploy','a'*64,True)]:
                with self.subTest(action=action,verifier=verifier),tempfile.TemporaryDirectory() as d:
                    path,sha=self.saved_plan(d,action,verifier)
                    if ok:ops.verify_plan(path,sha)
                    else:
                        with self.assertRaisesRegex(ops.OpsError,'invalid-verifier-hash'):ops.verify_plan(path,sha)

class ReleaseGateRegressions(unittest.TestCase):
    MANIFEST={'gitSha':'a'*40,'sourceState':'overlay','sourceOverlaySha256':'sha256:'+'0123456789ab'+'f'*52,
              'apiImageDigest':'sha256:'+'1'*64,'aiImageDigest':'sha256:'+'2'*64}
    def test_image_tag_binds_overlay_builds_distinctly(self):
        self.assertEqual('sha-'+'a'*40+'-ovl-0123456789ab',ops.image_tag(self.MANIFEST))
        self.assertEqual('sha-'+'a'*40,ops.image_tag({**self.MANIFEST,'sourceState':'clean'}))
    def test_digest_without_the_source_tag_is_refused(self):
        # Each repository answers with ITS digest, so only the tag binding can fail here.
        def fake(tags):
            return lambda service, operation, **kw: {'imageDetails': [{'imageDigest': kw['imageIds'][0]['imageDigest'], 'imageTags': tags}]}
        with patch.object(ops,'aws',side_effect=fake(['sha-'+'a'*40])):
            with self.assertRaisesRegex(ops.OpsError,'release-image-not-bound-to-source'):ops.verify_images(self.MANIFEST)
        with patch.object(ops,'aws',side_effect=fake([ops.image_tag(self.MANIFEST)])):
            ops.verify_images(self.MANIFEST)
    def test_placeholder_kto_secret_blocks_services(self):
        with patch.object(ops,'aws',return_value={'VersionIdsToStages':{'v1':['AWSCURRENT']}}):
            with self.assertRaisesRegex(ops.OpsError,'kto-secret-not-provisioned'):ops.require_kto_secret_provisioned()
        with patch.object(ops,'aws',return_value={'VersionIdsToStages':{'v1':['AWSPREVIOUS'],'v2':['AWSCURRENT']}}):
            ops.require_kto_secret_provisioned()

class OpsTaskRegressions(unittest.TestCase):
    def args(self, **overrides):
        from types import SimpleNamespace
        base={'task':'kto-smoke','content_id':'126508','content_type_id':'12','place_id':None,'owner_approval':None,'places':None}
        return SimpleNamespace(**{**base,**overrides})
    def test_actual_call_needs_the_callers_approval_and_a_record(self):
        base={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}
        with patch.dict(os.environ,base),patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
            os.environ.pop('NULLNULL_KTO_SMOKE_APPROVED',None)
            # A record string alone never becomes the approval variable.
            with self.assertRaisesRegex(ops.OpsError,'nullnull-kto-smoke-approved-not-set-by-caller'):
                ops.ops_task(self.args(owner_approval='owner approved in session'))
            with patch.dict(os.environ,{'NULLNULL_KTO_SMOKE_APPROVED':'true'}):
                with self.assertRaisesRegex(ops.OpsError,'owner-approval-record-required'):ops.ops_task(self.args())
            aws.assert_not_called()
    def test_ops_tasks_are_local_only(self):
        with patch.dict(os.environ,AuthModeRegressions.AMBIENT):
            with self.assertRaisesRegex(ops.OpsError,'ops-tasks-are-local-only'):ops.ops_task(self.args(owner_approval='owner approved in session'))
    def test_inputs_are_validated_before_any_call(self):
        with patch.dict(os.environ,{'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}),patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
            with self.assertRaisesRegex(ops.OpsError,'invalid-content-id'):ops.ops_task(self.args(task='kto-ingest',content_id='1; rm -rf /'))
            aws.assert_not_called()
    def test_a_demo_place_list_is_validated_before_any_call(self):
        base={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}
        with patch.dict(os.environ,base),patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
            os.environ.pop('NULLNULL_KTO_SMOKE_APPROVED',None)
            for bad,reason in [('','invalid-places'),('126508','invalid-places'),('126508:12,','invalid-places'),
                               ('0126508:12','invalid-places'),('126508:12;rm -rf /','invalid-places'),
                               ('126508:12,126508:12','duplicate-places')]:
                with self.subTest(places=bad),self.assertRaisesRegex(ops.OpsError,reason):
                    ops.ops_task(self.args(task='kto-demo-detail',places=bad))
            # A valid list passes validation and then still needs the owner's own approval variable.
            with self.assertRaisesRegex(ops.OpsError,'nullnull-kto-smoke-approved-not-set-by-caller'):
                ops.ops_task(self.args(task='kto-demo-detail',places='126508:12,126509:12'))
            aws.assert_not_called()
    def test_seoul_area_name_is_validated_before_any_call(self):
        base={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}
        with patch.dict(os.environ,base),patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
            for bad in ('', '../citydata', '광화문/덕수궁', 'x\nsecret'):
                with self.subTest(area_name=bad),self.assertRaisesRegex(ops.OpsError,'invalid-area-name'):
                    ops.ops_task(self.args(task='seoul-live-collect',area_name=bad))
            aws.assert_not_called()
    def test_live_mapping_plan_needs_owner_approved_bytes_before_any_aws_call(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'live-mappings.json'
            path.write_text(json.dumps({'mappings':[{'placeId':'00000000-0000-4000-8000-000000000001',
                'areaName':'서울숲','mappingType':'AREA_FALLBACK','confidence':0.75,'fallbackUsed':True,
                'verifiedAt':'2026-09-20T06:00:00Z','evidenceUrl':'https://data.seoul.go.kr/example'}]}))
            args=SimpleNamespace(task='curate-live-maps',plan_file=str(path),approved_plan_sha256=None,
                                 owner_approval='owner approved in session')
            with patch.dict(os.environ,{'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}),\
                 patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
                with self.assertRaisesRegex(ops.OpsError,'plan-sha256-not-approved'):
                    ops.ops_task(args)
                aws.assert_not_called()
    def test_replay_capture_plan_needs_owner_approved_snapshot_ids(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'replay.json'
            snapshot_id='00000000-0000-4000-8000-000000000001'
            path.write_text(json.dumps({'name':'seoul-pilot','capturedFrom':'2026-09-20T05:00:00Z',
                'capturedTo':'2026-09-20T05:10:00Z','snapshotIds':[snapshot_id]}))
            args=SimpleNamespace(task='capture-live-replay',plan_file=str(path),
                                 approved_plan_sha256=None,owner_approval='owner approved in session')
            with self.assertRaisesRegex(ops.OpsError,'plan-sha256-not-approved'):
                ops.curation_plan(args)
            args.approved_plan_sha256=ops.digest(path)
            assert ops.curation_plan(args)['ids']==[snapshot_id]
    def test_only_redacted_evidence_lines_are_echoed(self):
        allowed=['KTO_SMOKE_OK source=KTO_KOR_SERVICE_2 contentId=126508 contentTypeId=12 payloadHash=abc',
                 'KTO_SMOKE_SETTINGS KTO_SERVICE_KEY <- process env',
                 'Exception in thread "main" java.lang.IllegalStateException: KTO smoke failed: PROVIDER_ERROR (AUTH)',
                 'operations target=postgresql://db.example.rds.amazonaws.com:5432/nullnull environment=staging access=write schema=unchecked',
                 'operations target=unknown environment=staging access=write schema=unchecked',
                 'Exception in thread "main" java.lang.IllegalStateException: KTO smoke failed: OPERATIONS_TARGET_NOT_CONFIRMED']
        refused=['serviceKey=abcdef KTO_SMOKE_OK','2026-09-18 INFO jdbc:postgresql://db:5432/nullnull user=nullnull_app',
                 'KTO_SMOKE_OK '+'x'*500,
                 'operations target=postgresql://nullnull_app:pw@db:5432/nullnull environment=staging access=write schema=unchecked',
                 'operations target=postgresql://db:5432/nullnull?sslmode=require environment=staging access=write schema=unchecked',
                 'operations target=postgresql://db:5432/nullnull environment=staging access=write schema=unchecked password=x']
        for line in allowed: self.assertTrue(ops.OPS_LOG_LINE.match(line),line)
        for line in refused: self.assertFalse(ops.OPS_LOG_LINE.match(line),line)

class OperationsTargetRegressions(unittest.TestCase):
    """A writing ops task carries the database the caller named (OperationsContext, #183), checked against RDS first."""
    HOST='db.example.ap-northeast-2.rds.amazonaws.com'
    TARGET=f'postgresql://{HOST}:5432/nullnull'
    class Lock:
        owner='lock-owner'
        def __enter__(self): return self
        def __exit__(self,*a): return False
        def mutating(self): pass
    def run_ingest(self, stated, log=(), task='kto-ingest', area_name=None):
        import contextlib, io
        from types import SimpleNamespace
        calls=[]
        def fake(service,operation,**kw):
            calls.append((service,operation,kw))
            if (service,operation)==('rds','describe-db-instances'):
                return {'DBInstances':[{'Endpoint':{'Address':self.HOST,'Port':5432},'DBName':'nullnull'}]}
            if (service,operation)==('ecs','describe-task-definition'): return {'taskDefinition':{}}
            if (service,operation)==('ecs','run-task'): return {'tasks':[{'taskArn':'arn:aws:ecs:r:a:task/c/abc123'}]}
            if (service,operation)==('logs','get-log-events'): return {'events':[{'message':m} for m in log]}
            raise AssertionError((service,operation))
        env={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}
        if stated is not None: env[ops.OPERATIONS_TARGET]=stated
        out=io.StringIO()
        with patch.dict(os.environ,env),patch.object(ops,'identity'),patch.object(ops,'aws',side_effect=fake),\
             patch.object(ops,'output',side_effect=lambda stack,key,**kw:'s-a,s-b' if key=='AppSubnetIds' else key),\
             patch.object(ops,'DeploymentLock',self.Lock),patch.object(ops,'wait_task'),\
             patch.object(ops,'release_binding',return_value=({'releaseVersion':'v0.1.0-rc.12'},'synthetic')),\
             contextlib.redirect_stdout(out):
            if stated is None: os.environ.pop(ops.OPERATIONS_TARGET,None)
            error=None
            try:
                ops.ops_task(SimpleNamespace(task=task,content_id='126508',content_type_id='12',place_id=None,
                                             owner_approval=None,places=None,area_name=area_name,plan_file=None))
            except ops.OpsError as e:
                error=str(e)
        return error,calls,out.getvalue()
    def test_unset_or_another_database_stops_before_a_task_starts(self):
        for stated,reason in [(None,'operations-target-not-set-by-caller'),
                              (f'postgresql://other.{self.HOST}:5432/nullnull','operations-target-not-the-staging-database'),
                              (self.TARGET+'?sslmode=require','operations-target-not-the-staging-database')]:
            with self.subTest(stated=stated):
                error,calls,out=self.run_ingest(stated)
                self.assertIn(reason,error)
                self.assertNotIn(('ecs','run-task'),[(s,o) for s,o,_ in calls])
                self.assertIn(f'operations_target_required={ops.OPERATIONS_TARGET}={self.TARGET}',out)
    def test_the_named_database_travels_to_the_task_and_its_target_line_is_echoed(self):
        line=f'operations target={self.TARGET} environment=staging access=write schema=unchecked'
        error,calls,out=self.run_ingest(f'  {self.TARGET} ',log=[line,'2026 INFO jdbc:postgresql://x user=y'])
        self.assertIsNone(error)
        run=[kw for s,o,kw in calls if (s,o)==('ecs','run-task')]
        self.assertEqual(1,len(run))
        environment={e['name']:e['value'] for e in run[0]['overrides']['containerOverrides'][0]['environment']}
        self.assertEqual(self.TARGET,environment[ops.OPERATIONS_TARGET])
        self.assertIn('ops_log '+line,out);self.assertNotIn('jdbc:',out)
        self.assertIn('ops_task=kto-ingest result=succeeded',out)
    def test_seoul_task_requires_a_live_reading_and_receives_only_proxy_coordinates(self):
        missing,calls,_=self.run_ingest(self.TARGET,task='seoul-live-collect',area_name='서울숲공원')
        self.assertIn('seoul-collect-not-live',missing)
        accepted,calls,out=self.run_ingest(self.TARGET,log=['seoul_live_collect live=true'],
                                            task='seoul-live-collect',area_name='서울숲공원')
        self.assertIsNone(accepted)
        run=[kw for service,operation,kw in calls if (service,operation)==('ecs','run-task')]
        environment={entry['name']:entry['value'] for entry in run[0]['overrides']['containerOverrides'][0]['environment']}
        self.assertEqual('서울숲공원',environment['NULLNULL_SEOUL_AREA_NAME'])
        self.assertEqual('SeoulProxyUrl',environment['SEOUL_BASE_URL'])
        self.assertEqual('SeoulProxyHost',environment['SEOUL_ALLOWED_HOST'])
        self.assertNotIn('SEOUL_PROXY_TOKEN',environment)
        self.assertIn('ops_log seoul_live_collect live=true',out)

class SecretProvisioningRegressions(unittest.TestCase):
    def run_seoul(self, current, key='SYNTHETIC_SEOUL_KEY', *, ambient=False, denied=False, action='secrets'):
        import contextlib, io
        calls=[];out=io.StringIO();err=io.StringIO()
        def fake(service,operation,**kw):
            calls.append((service,operation,kw))
            if denied:raise ops.OpsError('aws-failed-secretsmanager-get-secret-value')
            if operation=='get-secret-value':return {'SecretString':current}
            return {}
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'apps/api').mkdir(parents=True)
            (root/'apps/api/.env.local').write_text('SEOUL_API_KEY='+key+'\n')
            env=AuthModeRegressions.AMBIENT if ambient else OperatorRegressions.PROFILE
            with patch.object(ops,'ROOT',root),patch.dict(os.environ,env),patch.object(ops,'identity'),\
                    patch.object(ops,'aws',side_effect=fake),\
                    patch.object(ops.sys,'argv',['staging_operator.py',action,'--seoul']),\
                    contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                try:code=ops.main()
                except SystemExit as failure:code=failure.code
        return code,calls,out.getvalue()+err.getvalue()

    def test_seoul_changes_only_key_and_keeps_proxy_token_and_other_fields(self):
        code,calls,out=self.run_seoul(json.dumps({'apiKey':'','proxyToken':'KEEP_THIS_TOKEN','extra':{'retain':True}}))
        self.assertEqual(0,code,out)
        self.assertEqual(['get-secret-value','put-secret-value'],[c[1] for c in calls])
        self.assertTrue(all(c[2]['SecretId']=='nullnull-stg/seoul-proxy' for c in calls))
        self.assertEqual({'apiKey':'SYNTHETIC_SEOUL_KEY','proxyToken':'KEEP_THIS_TOKEN','extra':{'retain':True}},
                         json.loads(calls[1][2]['SecretString']))
        self.assertIn('seoul_secret=provisioned changed=true',out)
        for value in ('SYNTHETIC_SEOUL_KEY','KEEP_THIS_TOKEN'):self.assertNotIn(value,out)

    def test_seoul_identical_key_does_not_create_another_secret_version(self):
        code,calls,out=self.run_seoul('{"apiKey":"SYNTHETIC_SEOUL_KEY","proxyToken":"KEEP_THIS_TOKEN"}')
        self.assertEqual(0,code,out)
        self.assertEqual(['get-secret-value'],[c[1] for c in calls])
        self.assertIn('changed=false',out)

    def test_seoul_refuses_broken_json_or_token_without_replacing_it(self):
        for current in (None,'','SENSITIVE_INVALID_JSON','null','[]','{}',
                        '{"apiKey":""}', '{"apiKey":"","proxyToken":""}',
                        '{"apiKey":"","proxyToken":"   "}', '{"apiKey":"","proxyToken":42}',
                        '{"apiKey":null,"proxyToken":"KEEP_THIS_TOKEN"}'):
            with self.subTest(current=current):
                code,calls,out=self.run_seoul(current)
                self.assertEqual(1,code,out)
                self.assertEqual(['get-secret-value'],[c[1] for c in calls])
                self.assertIn('seoul-secret-invalid',out)
                self.assertNotIn('SENSITIVE_INVALID_JSON',out)
                self.assertNotIn('KEEP_THIS_TOKEN',out)

    def test_seoul_invalid_local_key_does_not_read_or_write_remote_secret(self):
        for key in ('','short','bad key with spaces','x'*513):
            with self.subTest(length=len(key)):
                code,calls,out=self.run_seoul('{}',key)
                self.assertEqual(1,code,out);self.assertEqual([],calls)
                self.assertIn('local-seoul-key-missing-or-malformed',out)

    def test_seoul_refuses_ambient_credentials_and_reports_denied_read_without_a_write(self):
        code,calls,out=self.run_seoul('{}',ambient=True)
        self.assertEqual(1,code,out);self.assertEqual([],calls)
        self.assertIn('secret-provisioning-is-local-only',out)
        code,calls,out=self.run_seoul('{}',denied=True)
        self.assertEqual(1,code,out)
        self.assertEqual(['get-secret-value'],[c[1] for c in calls])
        self.assertNotIn('SYNTHETIC_SEOUL_KEY',out)

    def test_seoul_option_cannot_start_a_different_action(self):
        code,calls,out=self.run_seoul('{}',action='deploy')
        self.assertEqual(1,code,out);self.assertEqual([],calls)
        self.assertIn('seoul-option-requires-secrets',out)

    def test_seoul_secret_payload_uses_private_temporary_file_not_argv(self):
        import argparse, contextlib, io, stat
        requests=[];files=[];out=io.StringIO()
        def subprocess_boundary(argv, **kwargs):
            self.assertNotIn('SYNTHETIC_SEOUL_KEY',' '.join(argv))
            self.assertNotIn('KEEP_THIS_TOKEN',' '.join(argv))
            path=Path(argv[argv.index('--cli-input-json')+1].removeprefix('file://'))
            self.assertEqual(0o600,stat.S_IMODE(path.stat().st_mode))
            request=json.loads(path.read_text());requests.append(request);files.append(path)
            response={'SecretString':'{"apiKey":"","proxyToken":"KEEP_THIS_TOKEN"}'} if len(requests)==1 else {}
            return subprocess.CompletedProcess(argv,0,json.dumps(response),'')
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'apps/api').mkdir(parents=True)
            (root/'apps/api/.env.local').write_text('SEOUL_API_KEY=SYNTHETIC_SEOUL_KEY\n')
            with patch.object(ops,'ROOT',root),patch.dict(os.environ,OperatorRegressions.PROFILE),\
                    patch.object(ops,'identity'),patch.object(ops.subprocess,'run',side_effect=subprocess_boundary),\
                    contextlib.redirect_stdout(out):
                ops.provision_secrets(argparse.Namespace(seoul=True))
        self.assertEqual(2,len(requests))
        self.assertEqual({'apiKey':'SYNTHETIC_SEOUL_KEY','proxyToken':'KEEP_THIS_TOKEN'},
                         json.loads(requests[1]['SecretString']))
        self.assertTrue(all(not path.exists() for path in files))
        for value in ('SYNTHETIC_SEOUL_KEY','KEEP_THIS_TOKEN'):self.assertNotIn(value,out.getvalue())

    def test_secret_value_never_reaches_stdout_or_argv(self):
        import contextlib, io
        key='SYNTHETICKEY/abc+def=='
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'apps/api').mkdir(parents=True);(root/'apps/api/.env.local').write_text('KTO_SERVICE_KEY='+key+'\n')
            calls=[]
            def fake(service,operation,**kw):
                calls.append((service,operation,kw))
                if operation=='get-secret-value':return {'SecretString':'placeholder'}
                if operation=='describe-secret':return {'VersionIdsToStages':{'a':['AWSPREVIOUS'],'b':['AWSCURRENT']}}
                return {}
            out=io.StringIO()
            with patch.object(ops,'ROOT',root),patch.dict(os.environ,{'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}),patch.object(ops,'identity'),patch.object(ops,'aws',side_effect=fake),contextlib.redirect_stdout(out):
                ops.provision_secrets(None)
            self.assertNotIn(key,out.getvalue());self.assertIn('changed=true',out.getvalue())
            put=[c for c in calls if c[1]=='put-secret-value'][0]
            self.assertEqual(key,put[2]['SecretString'])
    def test_secret_provisioning_is_local_only(self):
        with patch.dict(os.environ,AuthModeRegressions.AMBIENT):
            with self.assertRaisesRegex(ops.OpsError,'secret-provisioning-is-local-only'):ops.provision_secrets(None)

class EvidenceRegressions(unittest.TestCase):
    LINE=('KTO_SMOKE_OK source=KTO_KOR_SERVICE_2 contentId=126508 contentTypeId=12 snapshotId=s-1 '
          'collectorRunId=c-1 sourceRegistryVersion=4 payloadHash=abc fetchedAt=2026-09-18T13:00:00Z called=true')
    RECORD={'releaseVersion':'v0.1.0-rc.1','gitSha':'a'*40}
    def test_report_is_what_the_repository_gate_accepts_for_this_release(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(ops,'ROOT',Path(d)),patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'aws_cli',return_value=subprocess.CompletedProcess([],0,'','')),patch.object(ops,'run') as run:
                ops.write_actual_call_report([self.LINE],self.RECORD)
            report=json.loads((Path(d)/'.artifacts/aws/evidence/actual-call-v0.1.0-rc.1.json').read_text())
            gate=subprocess.run(['python3',str(ROOT/'scripts/check_actual_call_evidence.py'),str(Path(d)/'.artifacts/aws/evidence/actual-call-v0.1.0-rc.1.json'),'--release','v0.1.0-rc.1','--require-verified'],capture_output=True,text=True)
            self.assertEqual(0,gate.returncode,gate.stderr)
            self.assertEqual('staging',report['environment']);self.assertEqual('OK',report['calls'][0]['outcome'])
            self.assertIn('--require-verified',[str(a) for a in run.call_args.args[0]])
    def test_no_evidence_line_means_no_report(self):
        with self.assertRaisesRegex(ops.OpsError,'kto-smoke-evidence-line-missing'):ops.write_actual_call_report([],self.RECORD)
    def test_a_line_that_does_not_say_this_run_called_writes_no_report(self):
        # An image older than the forced smoke prints no `called`; a stored snapshot handed back prints called=false.
        # Either would otherwise become this release's CMP-KTO-003 evidence for a call it never made.
        old_image=self.LINE.replace(' called=true','')
        for line,reason in [(old_image,'kto-smoke-evidence-field-missing-called'),
                            (self.LINE.replace('called=true','called=false'),'kto-smoke-did-not-call'),
                            (self.LINE.replace('called=true','called=yes'),'kto-smoke-did-not-call')]:
            with self.subTest(line=line[-24:]),patch.object(ops,'write_private') as write,patch.object(ops,'aws_cli') as upload:
                with self.assertRaisesRegex(ops.OpsError,reason):ops.write_actual_call_report([line],self.RECORD)
                write.assert_not_called();upload.assert_not_called()
    def test_the_cached_line_passes_the_log_allowlist_so_the_owner_sees_why_it_stopped(self):
        cached=self.LINE.replace('KTO_SMOKE_OK','KTO_SMOKE_CACHED').replace('called=true','called=false')
        self.assertTrue(ops.OPS_LOG_LINE.match(cached))
        longest=('KTO_SMOKE_CACHED source=KTO_KOR_SERVICE_2 contentId='+'9'*30+' contentTypeId='+'9'*30
                 +' snapshotId='+'f'*36+' collectorRunId='+'f'*36+' sourceRegistryVersion=99 payloadHash='+'f'*64
                 +' fetchedAt=2026-09-18T13:00:00.123456Z called=false')
        self.assertTrue(ops.OPS_LOG_LINE.match(longest),len(longest))

class SmokeImageBindingRegressions(unittest.TestCase):
    """The smoke's report names the deployed release, so it must run that release's image (checked twice)."""
    DIGEST='sha256:'+'a'*64
    TARGET=OperationsTargetRegressions.TARGET
    LINE=EvidenceRegressions.LINE
    def run_smoke(self, record, ops_image, ops_release='v0.1.0-rc.2'):
        import contextlib, io
        from types import SimpleNamespace
        calls=[]
        def fake(service,operation,**kw):
            calls.append((service,operation,kw))
            if (service,operation)==('rds','describe-db-instances'):
                return {'DBInstances':[{'Endpoint':{'Address':OperationsTargetRegressions.HOST,'Port':5432},'DBName':'nullnull'}]}
            if (service,operation)==('ecs','describe-task-definition'):
                return {'taskDefinition':{'containerDefinitions':[{'name':'ops','image':ops_image,
                        'environment':[{'name':'APP_RELEASE_VERSION','value':ops_release}]}]}}
            if (service,operation)==('ecs','run-task'): return {'tasks':[{'taskArn':'arn:aws:ecs:r:a:task/c/abc123'}]}
            if (service,operation)==('logs','get-log-events'): return {'events':[{'message':self.LINE}]}
            raise AssertionError((service,operation))
        env={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12,
             'NULLNULL_KTO_SMOKE_APPROVED':'true',ops.OPERATIONS_TARGET:self.TARGET}
        with patch.dict(os.environ,env),patch.object(ops,'identity'),patch.object(ops,'aws',side_effect=fake),\
             patch.object(ops,'output',side_effect=lambda stack,key,**kw:'s-a,s-b' if key=='AppSubnetIds' else key),\
             patch.object(ops,'DeploymentLock',OperationsTargetRegressions.Lock),patch.object(ops,'wait_task') as wait,\
             patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=record),\
             patch.object(ops,'write_actual_call_report') as report,contextlib.redirect_stdout(io.StringIO()):
            error=None
            try:
                ops.ops_task(SimpleNamespace(task='kto-smoke',content_id='126508',content_type_id='12',place_id=None,
                                             owner_approval='owner approved in session',places=None))
            except ops.OpsError as e:
                error=str(e)
        return error,[(s,o) for s,o,_ in calls],wait,report
    def test_the_executed_image_is_checked_against_the_deployed_release_and_the_same_record_is_reported(self):
        record={'releaseVersion':'v0.1.0-rc.2','gitSha':'a'*40,'releaseManifest':{'apiImageDigest':self.DIGEST}}
        error,calls,wait,report=self.run_smoke(record,'1.dkr.ecr/nullnull-api@'+self.DIGEST)
        self.assertIsNone(error)
        self.assertEqual(self.DIGEST,wait.call_args.args[5])
        report.assert_called_once_with([self.LINE],record)
    def test_a_smoke_that_would_run_another_image_or_has_no_release_stops_before_the_task(self):
        other='1.dkr.ecr/nullnull-api@sha256:'+'b'*64
        for record,image,reason in [
                (None,other,'no-deployed-release-record'),
                ({'releaseVersion':'v0.1.0-rc.2','gitSha':'a'*40,'releaseManifest':{}},other,'deployed-release-has-no-api-digest'),
                ({'releaseVersion':'v0.1.0-rc.2','gitSha':'a'*40,'releaseManifest':{'apiImageDigest':self.DIGEST}},other,
                 'ops-image-not-the-deployed-release')]:
            with self.subTest(reason=reason):
                error,calls,wait,report=self.run_smoke(record,image)
                self.assertIn(reason,error or '')
                self.assertNotIn(('ecs','run-task'),calls)
                report.assert_not_called()
    def test_one_digest_shared_by_two_releases_is_not_enough_to_name_the_release(self):
        # rc.1000 and rc.1001 shared an API digest; a failed deploy of the next release can leave its ops definition
        # behind while current.json still names the previous one.
        record={'releaseVersion':'v0.1.0-rc.2','gitSha':'a'*40,'releaseManifest':{'apiImageDigest':self.DIGEST}}
        error,calls,wait,report=self.run_smoke(record,'1.dkr.ecr/nullnull-api@'+self.DIGEST,ops_release='v0.1.0-rc.3')
        self.assertIn('ops-definition-not-the-deployed-release',error or '')
        self.assertNotIn(('ecs','run-task'),calls)
        report.assert_not_called()

class CurationTaskRegressions(unittest.TestCase):
    """curate-hours: the owner approves the exact plan bytes, and the task imports those bytes or nothing."""
    DIGEST='sha256:'+'a'*64
    RELEASE='v0.1.0-rc.2'
    PLACE='01a0b825-4f15-7e7b-b30c-87cf71861c9c'
    # Verbatim what CuratedHoursImportMainTest shows CuratedHoursImportMain printing.
    JAVA_LINES=['curated_hours 01a0b825-4f15-7e7b-b30c-87cf71861c9c RECORDED (windows=48)',
                'curated_hours 01a0b825-4f15-7e7b-b30c-87cf71861c9c REPLACED (windows=48)',
                'curated_hours_recorded=2','curated_hours_failed reason=OPERATIONS_TARGET_NOT_CONFIRMED',
                'curated_hours_failed reason=IllegalStateException']
    def plan(self, **overrides):
        place={'placeId':self.PLACE,'evidenceUrl':'https://royal.khs.go.kr/ROYAL/contents/R702000000.do',
               'observedAt':'2026-09-13T18:40:00Z','outcome':'OBSERVED',
               'windows':[{'date':'2026-10-01','state':'OPEN','opensAt':'09:00:00','closesAt':'18:00:00'}]}
        place.update(overrides)
        return json.dumps({'places':[place]},ensure_ascii=False,indent=2).encode()
    TASK='curate-hours'
    PUBLIC_URL='https://d54awmnmi4c3z.cloudfront.net'
    def served(self, url):
        raise AssertionError('the hours task fetches no cover: '+url)
    def default_log(self, data, sha):
        return [f'curated_hours_plan sha256={sha} bytes={len(data)}']+self.JAVA_LINES[:1]+['curated_hours_recorded=1']
    def run_curate(self, data, approved=None, owner='owner approved in session', log=None, plan_file=True,
                   image=None, release=None, task_failure=None):
        import contextlib, gzip as gz, hashlib, io
        from types import SimpleNamespace
        sha=hashlib.sha256(data).hexdigest()
        calls=[]
        if log is None: log=self.default_log(data, sha)
        def fake(service,operation,**kw):
            calls.append((service,operation,kw))
            if (service,operation)==('rds','describe-db-instances'):
                return {'DBInstances':[{'Endpoint':{'Address':OperationsTargetRegressions.HOST,'Port':5432},'DBName':'nullnull'}]}
            if (service,operation)==('ecs','describe-task-definition'):
                return {'taskDefinition':{'containerDefinitions':[{'name':'ops','image':image or '1.dkr.ecr/nullnull-api@'+self.DIGEST,
                        'environment':[{'name':'APP_RELEASE_VERSION','value':release or self.RELEASE}]}]}}
            if (service,operation)==('ecs','run-task'): return {'tasks':[{'taskArn':'arn:aws:ecs:r:a:task/c/abc123'}]}
            if (service,operation)==('logs','get-log-events'): return {'events':[{'message':m} for m in log]}
            raise AssertionError((service,operation))
        record={'releaseVersion':self.RELEASE,'gitSha':'a'*40,'releaseManifest':{'apiImageDigest':self.DIGEST}}
        env={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12,
             ops.OPERATIONS_TARGET:OperationsTargetRegressions.TARGET}
        out=io.StringIO()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'hours.json';path.write_bytes(data)
            with patch.dict(os.environ,env),patch.object(ops,'identity') as ident,patch.object(ops,'aws',side_effect=fake),\
                 patch.object(ops,'output',side_effect=lambda stack,key,**kw:'s-a,s-b' if key=='AppSubnetIds'
                              else self.PUBLIC_URL if key=='PublicUrl' else key),\
                 patch.object(ops,'fetch_public',side_effect=lambda url:self.served(url)),\
                 patch.object(ops,'DeploymentLock',OperationsTargetRegressions.Lock),\
                 patch.object(ops,'wait_task',side_effect=task_failure),\
                 patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=record),\
                 patch.object(ops,'ROOT',Path(d)),patch.object(ops,'aws_cli',return_value=subprocess.CompletedProcess([],0,'','')) as upload,\
                 contextlib.redirect_stdout(out):
                error=None
                try:
                    ops.ops_task(SimpleNamespace(task=self.TASK,content_id=None,content_type_id=None,place_id=None,
                                                 owner_approval=owner,places=None,plan_file=str(path) if plan_file else None,
                                                 approved_plan_sha256=sha if approved is None else approved))
                except ops.OpsError as e:
                    error=str(e)
                kept=(Path(d)/'.artifacts/aws/evidence'/f'curation-{sha}.json')
                kept=kept.read_bytes() if kept.exists() else None
        run=[kw for s_,o,kw in calls if (s_,o)==('ecs','run-task')]
        return {'error':error,'calls':[(s_,o) for s_,o,_ in calls],'run':run,'out':out.getvalue(),'sha':sha,
                'upload':upload,'identity':ident,'kept':kept}
    def test_the_approved_bytes_travel_to_the_task_and_are_kept_as_evidence(self):
        import gzip as gz, base64 as b64
        data=self.plan()
        r=self.run_curate(data)
        self.assertIsNone(r['error'],r['out'])
        env={e['name']:e['value'] for e in r['run'][0]['overrides']['containerOverrides'][0]['environment']}
        self.assertEqual('io.nullnull.catalog.infrastructure.curation.CuratedHoursImportMain',env['LOADER_MAIN'])
        self.assertEqual(data,gz.decompress(b64.b64decode(env['NULLNULL_HOURS_PLAN_GZIP_BASE64'])))
        self.assertEqual(r['sha'],env['NULLNULL_HOURS_PLAN_SHA256'])
        self.assertEqual(OperationsTargetRegressions.TARGET,env[ops.OPERATIONS_TARGET])
        self.assertNotIn('APP_CONTEST_PROFILE',env)
        self.assertIn(f'plan_sha256={r["sha"]} bytes={len(data)} places=1 place_ids={self.PLACE}',r['out'])
        self.assertIn('approved_plan_sha256='+r['sha'],r['out'])
        self.assertEqual(data,r['kept'])
        key=f'evidence/curation/{self.RELEASE}/{r["sha"]}.json'
        self.assertIn(f's3://b/{key}',[str(a) for a in r['upload'].call_args.args[0]])
        self.assertIn('curation_plan=recorded task=curate-hours sha256='+r['sha'],r['out'])
    def test_a_plan_that_is_not_approved_or_not_a_plan_stops_before_any_aws_call(self):
        import random
        rng=random.Random(7)  # one generator: a fresh seed per character repeats one character and compresses away
        noise=''.join(rng.choice('0123456789abcdef') for _ in range(12000))
        cases=[(self.plan(),{'plan_file':False},'plan-file-required'),
               (b'not json',{},'plan-file-not-json'),
               (self.plan(placeId='<BE: staging UUID>'),{},'plan-file-has-placeholders'),
               (json.dumps({'places':[]}).encode(),{},'plan-file-has-no-places'),
               (self.plan(placeId='not-a-uuid'),{},'plan-file-place-id-not-a-uuid'),
               (self.plan(),{'approved':'0'*64},'plan-sha256-not-approved'),
               (self.plan(),{'owner':'short'},'owner-approval-record-required'),
               (self.plan(evidenceUrl='https://example.test/'+noise),{},'plan-too-large-for-task-overrides')]
        for data,kw,reason in cases:
            with self.subTest(reason=reason):
                r=self.run_curate(data,**kw)
                self.assertIn(reason,r['error'] or '',r['out'])
                self.assertEqual([],r['calls']);r['identity'].assert_not_called()
    def test_a_plan_file_is_refused_for_a_task_that_imports_no_plan(self):
        from types import SimpleNamespace
        with patch.dict(os.environ,{'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}),\
             patch.object(ops,'identity') as ident,patch.object(ops,'aws') as aws:
            with self.assertRaisesRegex(ops.OpsError,'plan-file-not-accepted'):
                ops.ops_task(SimpleNamespace(task='kto-ingest',content_id='126508',content_type_id='12',place_id=None,
                                             owner_approval=None,places=None,plan_file='/tmp/x.json',approved_plan_sha256=None))
            aws.assert_not_called();ident.assert_not_called()
    def test_a_task_that_did_not_echo_the_approved_sha_records_nothing(self):
        data=self.plan()
        for log in [[],['curated_hours_plan sha256='+'0'*64+f' bytes={len(data)}'],
                    [f'curated_hours_plan sha256={__import__("hashlib").sha256(data).hexdigest()} bytes=1']]:
            with self.subTest(log=log):
                r=self.run_curate(data,log=log)
                self.assertIn('curation-plan-echo-mismatch',r['error'] or '')
                r['upload'].assert_not_called();self.assertIsNone(r['kept'])
    def test_success_is_read_from_the_imports_own_lines_not_from_the_exit_code_alone(self):
        import hashlib
        data=self.plan();sha=hashlib.sha256(data).hexdigest();plan_line=f'curated_hours_plan sha256={sha} bytes={len(data)}'
        for log,reason in [([plan_line,'curated_hours_failed reason=DataIntegrityViolationException'],'curation-import-failed'),
                           ([plan_line],'curation-not-all-places-recorded'),
                           ([plan_line,'curated_hours_recorded=0'],'curation-not-all-places-recorded')]:
            with self.subTest(reason=reason):
                r=self.run_curate(data,log=log)
                self.assertIn(reason,r['error'] or '')
                r['upload'].assert_not_called();self.assertIsNone(r['kept'])
    def test_a_curate_task_runs_only_on_the_recorded_releases_image_and_a_failed_one_records_nothing(self):
        for kw,reason in [({'image':'1.dkr.ecr/nullnull-api@sha256:'+'b'*64},'ops-image-not-the-deployed-release'),
                          ({'release':'v0.1.0-rc.1'},'ops-definition-not-the-deployed-release')]:
            with self.subTest(reason=reason):
                r=self.run_curate(self.plan(),**kw)
                self.assertIn(reason,r['error'] or '')
                self.assertNotIn(('ecs','run-task'),r['calls'])
        r=self.run_curate(self.plan(),task_failure=ops.OpsError('task-failed'))
        self.assertIn('task-failed',r['error'] or '')
        r['upload'].assert_not_called();self.assertIsNone(r['kept'])
    def test_the_lines_the_hours_import_prints_pass_and_nothing_that_carries_more(self):
        sha='b'*64
        allowed=[f'curated_hours_plan sha256={sha} bytes=34930']+self.JAVA_LINES
        refused=['curated_hours 01a0b825-4f15-7e7b-b30c-87cf71861c9c RECORDED (windows=48) https://royal.khs.go.kr/x',
                 f'curated_hours_plan sha256={sha} bytes=13 title=경복궁',
                 'curated_hours_failed reason=IllegalStateException: no curated hours plan at /tmp/x',
                 'curated_hours_recorded='+'9'*500,
                 'curated_hours 01a0b825-4f15-7e7b-b30c-87cf71861c9c DELETED (windows=48)']
        for line in allowed: self.assertTrue(ops.OPS_LOG_LINE.match(line),line)
        for line in refused: self.assertFalse(ops.OPS_LOG_LINE.match(line),line)

class CuratedPostsTaskRegressions(unittest.TestCase):
    """curate-posts (#183): the same approved-bytes path as the hours, with the posts plan's own shape and lines.

    The machinery is CurationTaskRegressions.run_curate, borrowed rather than inherited: inheriting would rerun every
    hours assertion against the posts task.
    """
    POST='01a0b463-4600-7183-8000-000000000001'
    # Verbatim what CuratedPostImportMainTest shows CuratedPostImportMain printing.
    JAVA_LINES=['curated_post 01a0b463-4600-7183-8000-000000000001 PUBLISHED (2 place(s))',
                'curated_post 01a0b463-4600-7183-8000-000000000001 ALREADY_PRESENT (left as it is)',
                'curated_posts_published=1 of 2','curated_posts_failed reason=OPERATIONS_TARGET_NOT_CONFIRMED',
                'curated_posts_failed reason=IllegalStateException']
    COVER=b'the approved cover bytes'
    URL='https://d54awmnmi4c3z.cloudfront.net/covers/01-gyeongbokgung.jpg'
    def plan(self, url=URL, places=None):
        post={'id':self.POST,'title':'담장을 따라 걷는 하루','body':'본문','publishedAt':'2026-09-18T03:00:00Z',
              'cover':{'url':url,'alt':'근정전 앞 넓은 마당','checksum':__import__('hashlib').sha256(self.COVER).hexdigest()},
              'places':places if places is not None else [{'placeId':CurationTaskRegressions.PLACE,'primary':True}],
              '_source_file':'docs/contest/covers/01-gyeongbokgung.jpg'}
        return json.dumps({'posts':[post]},ensure_ascii=False,indent=2).encode()
    def run_curate(self, data, rerun=False, served=None, **kw):
        runner=CurationTaskRegressions('run_curate')
        runner.TASK='curate-posts'
        runner.fetched=[]
        def serve(url):
            runner.fetched.append(url)
            if isinstance(served,Exception): raise served
            return self.COVER if served is None else served
        runner.served=serve
        runner.default_log=lambda d,sha:[f'curated_posts_plan sha256={sha} bytes={len(d)}',
                                         self.JAVA_LINES[1] if rerun else self.JAVA_LINES[0],
                                         f'curated_posts_published={0 if rerun else 1} of 1']
        result=runner.run_curate(data,**kw)
        result['fetched']=runner.fetched
        return result
    def test_the_approved_posts_plan_travels_to_its_main_and_is_kept_as_evidence(self):
        import gzip as gz, base64 as b64
        data=self.plan()
        r=self.run_curate(data)
        self.assertIsNone(r['error'],r['out'])
        env={e['name']:e['value'] for e in r['run'][0]['overrides']['containerOverrides'][0]['environment']}
        self.assertEqual('io.nullnull.social.infrastructure.curation.CuratedPostImportMain',env['LOADER_MAIN'])
        self.assertEqual(data,gz.decompress(b64.b64decode(env['NULLNULL_POSTS_PLAN_GZIP_BASE64'])))
        self.assertEqual(r['sha'],env['NULLNULL_POSTS_PLAN_SHA256'])
        self.assertNotIn('NULLNULL_HOURS_PLAN_GZIP_BASE64',env)
        self.assertIn(f'plan_sha256={r["sha"]} bytes={len(data)} posts=1 place_ids={CurationTaskRegressions.PLACE}',r['out'])
        self.assertEqual(data,r['kept'])
        self.assertIn('curation_plan=recorded task=curate-posts sha256='+r['sha'],r['out'])
        # The cover was fetched from the deployed edge and matched before the task was started.
        self.assertEqual([self.URL],r['fetched'])
        self.assertIn('covers_verified=1 origin=https://d54awmnmi4c3z.cloudfront.net/covers/',r['out'])
    def test_a_rerun_of_an_approved_plan_that_publishes_nothing_new_still_succeeds(self):
        # ALREADY_PRESENT is the importer's idempotence, not a failure: "0 of 1" is every post accounted for.
        r=self.run_curate(self.plan(),rerun=True)
        self.assertIsNone(r['error'],r['out'])
    def test_a_posts_plan_that_is_not_a_plan_stops_before_any_aws_call(self):
        cases=[(json.dumps({'posts':[]}).encode(),'plan-file-has-no-posts'),
               (self.plan(url='<BE: https://<도메인>/covers/01-gyeongbokgung.jpg>'),'plan-file-has-placeholders'),
               (self.plan(url='http://d54awmnmi4c3z.cloudfront.net/covers/01-gyeongbokgung.jpg'),'plan-file-cover-url-not-https'),
               (self.plan(places=[]),'plan-file-post-has-no-place'),
               (self.plan(places=[{'placeId':'not-a-uuid','primary':True}]),'plan-file-place-id-not-a-uuid'),
               # An hours plan handed to the posts task is refused for its shape, not imported as something else.
               (CurationTaskRegressions().plan(),'plan-file-has-no-posts')]
        for data,reason in cases:
            with self.subTest(reason=reason):
                r=self.run_curate(data)
                self.assertIn(reason,r['error'] or '',r['out'])
                self.assertEqual([],r['calls']);r['identity'].assert_not_called()
    def test_success_is_read_from_the_publications_own_lines(self):
        import hashlib
        data=self.plan();sha=hashlib.sha256(data).hexdigest();plan_line=f'curated_posts_plan sha256={sha} bytes={len(data)}'
        published,present=self.JAVA_LINES[0],self.JAVA_LINES[1]
        other='curated_post 01a0b463-4600-7183-8000-000000000009 PUBLISHED (1 place(s))'
        for log,reason in [([plan_line,published,'curated_posts_failed reason=CurationException'],'curation-import-failed'),
                           ([plan_line],'curation-not-all-posts-published'),
                           # A total alone is not a post accounted for: the numerator is not checked by a regex.
                           ([plan_line,'curated_posts_published=1 of 1'],'curation-not-all-posts-published'),
                           ([plan_line,published,'curated_posts_published=9999 of 1'],'curation-not-all-posts-published'),
                           # The published count is the PUBLISHED lines, not whatever the total claims.
                           ([plan_line,present,'curated_posts_published=1 of 1'],'curation-not-all-posts-published'),
                           # Every plan id once: not another post, not the same one twice.
                           ([plan_line,other,'curated_posts_published=1 of 1'],'curation-not-all-posts-published'),
                           ([plan_line,published,published,'curated_posts_published=2 of 1'],'curation-not-all-posts-published'),
                           ([plan_line,published,'curated_posts_published=1 of 2'],'curation-not-all-posts-published'),
                           # The hours' "done" line does not count for the posts.
                           ([plan_line,published,'curated_hours_recorded=1'],'curation-not-all-posts-published'),
                           ([f'curated_hours_plan sha256={sha} bytes={len(data)}',published,'curated_posts_published=1 of 1'],
                            'curation-plan-echo-mismatch')]:
            with self.subTest(reason=reason,log=log[-1]):
                r=self.run_curate(data,log=log)
                self.assertIn(reason,r['error'] or '')
                r['upload'].assert_not_called();self.assertIsNone(r['kept'])
    def test_a_cover_the_deployed_edge_does_not_serve_as_approved_stops_before_the_task(self):
        for data,kw,reason in [(self.plan(url='https://dother123.cloudfront.net/covers/01-gyeongbokgung.jpg'),{},
                                'cover-not-on-the-deployed-edge'),
                               (self.plan(),{'served':b'a different photo'},'cover-not-served-as-approved'),
                               (self.plan(),{'served':ops.OpsError('cover-not-served-as-approved')},'cover-not-served-as-approved')]:
            with self.subTest(reason=reason,kw=kw):
                r=self.run_curate(data,**kw)
                self.assertIn(reason,r['error'] or '')
                self.assertNotIn(('ecs','run-task'),r['calls'])
                r['upload'].assert_not_called();self.assertIsNone(r['kept'])
    def test_the_lines_the_posts_import_prints_pass_and_nothing_that_carries_more(self):
        sha='b'*64
        allowed=[f'curated_posts_plan sha256={sha} bytes=7021']+self.JAVA_LINES
        refused=['curated_post 01a0b463-4600-7183-8000-000000000001 PUBLISHED (2 place(s)) 담장을 따라 걷는 하루',
                 'curated_post 01a0b463-4600-7183-8000-000000000001 PUBLISHED (https://d54awmnmi4c3z.cloudfront.net/covers/x.jpg)',
                 f'curated_posts_plan sha256={sha} bytes=13 title=담장',
                 'curated_posts_failed reason=IllegalStateException: no curation plan at /tmp/x',
                 'curated_posts_published='+'9'*500+' of 5',
                 'curated_post 01a0b463-4600-7183-8000-000000000001 DELETED (left as it is)']
        for line in allowed: self.assertTrue(ops.OPS_LOG_LINE.match(line),line)
        for line in refused: self.assertFalse(ops.OPS_LOG_LINE.match(line),line)

class FetchPublicRegressions(unittest.TestCase):
    """The one call in the posts path that reaches outside AWS: a 200 at the exact URL, or a refusal."""
    def test_a_200_is_the_bytes_and_a_missing_or_moved_cover_is_refused(self):
        import http.server, threading
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path=='/covers/ok.jpg':
                    self.send_response(200);self.end_headers();self.wfile.write(b'cover bytes')
                elif self.path=='/covers/moved.jpg':
                    self.send_response(302);self.send_header('Location','/covers/ok.jpg');self.end_headers()
                else:
                    self.send_response(404);self.end_headers()
            def log_message(self,*args):pass
        server=http.server.HTTPServer(('127.0.0.1',0),Handler)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            base=f'http://127.0.0.1:{server.server_address[1]}'
            self.assertEqual(b'cover bytes',ops.fetch_public(base+'/covers/ok.jpg'))
            # A redirect lands somewhere the plan did not name; a 404 is a broken cover.
            for path in ['/covers/moved.jpg','/covers/missing.jpg']:
                with self.subTest(path=path):
                    with self.assertRaisesRegex(ops.OpsError,'cover-not-served-as-approved'):
                        ops.fetch_public(base+path)
        finally:
            server.shutdown();server.server_close()

class SecretScanRegressions(unittest.TestCase):
    """BA-006-T2: every recorded release's bundle, the deployed images and the retained logs, with the real values.

    The images are built the way a containerd store saves them - gzip layers - with the key deflated inside a jar, a jar
    inside that jar, a zip with no suffix and an xz file, because a scan that did not open them would find nothing
    there and say clean.
    """
    KTO='abc+def/ghi=jkl'
    VERIFIER='v'*43
    GROUPS={'nullnull-stg-api':'NullnullStgPlatform-ApiLogs1','nullnull-stg-ai':'NullnullStgPlatform-AiLogs1',
            'OpsTaskDefinitionArn':'NullnullStgPlatform-MigrationLogs1','MigrationTaskDefinitionArn':'NullnullStgPlatform-MigrationLogs1'}
    @staticmethod
    def zipped(entries):
        import io, zipfile
        buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED) as archive:
            for name,data in entries.items():archive.writestr(name,data)
        return buffer.getvalue()
    def image_tar(self, path, jar_text=b'spring.application.name=nullnull', nested_text=b'library resource',
                  plain_text=b'ID=synthetic', odd_zip_text=b'nothing', xz_text=b'nothing', broken=False):
        import gzip as gz, io, lzma, tarfile as tf
        jar=self.zipped({'BOOT-INF/classes/application.yaml':jar_text,
                         'BOOT-INF/lib/library.jar':self.zipped({'library.properties':nested_text})})
        layer=io.BytesIO()
        with tf.open(fileobj=layer,mode='w') as tar:
            for name,data in {'app/nullnull-api.jar':jar,'etc/os-release':plain_text,
                              'opt/bundle.dat':self.zipped({'inside.txt':odd_zip_text}),
                              'usr/share/doc/notes':lzma.compress(xz_text),
                              **({'var/cache/damaged':b'\x1f\x8b'+b'not really gzip'*40} if broken else {})}.items():
                info=tf.TarInfo(name);info.size=len(data);tar.addfile(info,io.BytesIO(data))
        blobs={'blobs/sha256/'+'1'*64:gz.compress(layer.getvalue()),'blobs/sha256/'+'2'*64:b'{"config":{"Env":["PATH=/usr/bin"]}}',
               'index.json':b'{"schemaVersion":2}'}
        with tf.open(path,'w') as tar:
            for name,data in blobs.items():
                info=tf.TarInfo(name);info.size=len(data);tar.addfile(info,io.BytesIO(data))
    def release_archive(self, root, n, web):
        import tarfile as tf
        plan=root/f'build/plan-{n}';(plan/'assembly/asset.web').mkdir(parents=True)
        (plan/'assembly/asset.web/index.html').write_bytes(web)
        (plan/'assembly/NullnullStgWebEdge.template.json').write_text('{"Resources":{}}')
        (plan/'plan.json').write_text(json.dumps({'assemblySha256':ops.tree_digest(plan/'assembly'),'n':n}))
        (plan/'release.json').write_text(json.dumps({'webArtifactSha256':'sha256:'+ops.tree_digest(plan/'assembly/asset.web')}))
        sha=ops.digest(plan/'plan.json')
        archive=root/f'build/{sha}.tgz'
        with tf.open(archive,'w:gz') as tar:
            for name in ['plan.json','release.json','assembly']:tar.add(plan/name,arcname=name)
        return sha,archive
    def run_scan(self, logs=None, web=b'<!doctype html>', old_web=None, image=None, without_images=False, docker=True,
                 unrecorded=False, task_secrets=(), since=None):
        import contextlib, io
        from types import SimpleNamespace
        logs={'NullnullStgPlatform-ApiLogs1':['Started NullnullApiApplication']} if logs is None else logs
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'scripts').mkdir()
            shutil.copyfile(ROOT/'scripts/check_secret_exposure.py',root/'scripts/check_secret_exposure.py')
            releases=[self.release_archive(root,0,web)]
            if old_web is not None:releases.append(self.release_archive(root,1,old_web))
            archives={f'releases/{sha}/plan.tgz':a for sha,a in releases}
            current={'planKey':f'releases/{releases[0][0]}/plan.tgz','planSha256':releases[0][0],
                     'releaseVersion':'v0.1.0-rc.9','deployedAt':'2026-09-19T00:00:00+00:00',
                     'releaseManifest':{'apiImageDigest':'sha256:'+'a'*64,'aiImageDigest':'sha256:'+'b'*64}}
            uploads,docker_calls,log_calls=[],[],[]
            def fake_aws(service,operation,**kw):
                if (service,operation)==('secretsmanager','get-secret-value'):
                    return {'SecretString':self.KTO if 'kto' in kw['SecretId'] else self.VERIFIER}
                if (service,operation)==('ecs','describe-task-definition'):
                    return {'taskDefinition':{'containerDefinitions':[{'name':'c','secrets':[{'name':s} for s in task_secrets],
                            'logConfiguration':{'options':{'awslogs-group':self.GROUPS[kw['taskDefinition']]}}}]}}
                raise AssertionError((service,operation))
            def fake_cli(args,**kw):
                args=[str(a) for a in args]
                if args[:2]==['s3api','list-objects-v2']:
                    keys=[] if unrecorded else list(archives)
                    return subprocess.CompletedProcess(args,0,json.dumps({'Contents':[{'Key':k} for k in keys+['releases/x/other.json']]}),'')
                if args[:2]==['s3','cp'] and args[2].startswith('s3://'):
                    shutil.copyfile(archives[args[2][len('s3://b/'):]],args[3]);return subprocess.CompletedProcess(args,0,'','')
                if args[:2]==['s3','cp']:
                    uploads.append((args[3],json.loads(Path(args[2]).read_text())));return subprocess.CompletedProcess(args,0,'','')
                if args[:2]==['logs','filter-log-events']:
                    log_calls.append(args)
                    group=args[args.index('--log-group-name')+1]
                    return subprocess.CompletedProcess(args,0,json.dumps({'events':[{'message':m} for m in logs.get(group,[])]}),'')
                if args[:2]==['ecr','get-login-password']:return subprocess.CompletedProcess(args,0,'token\n','')
                raise AssertionError(args)
            def fake_run(command,**kw):
                docker_calls.append((command,kw.get('env',{}).get('DOCKER_CONFIG')))
                if command[:2]==['docker','save']:self.image_tar(command[3],**(image or {}))
                return subprocess.CompletedProcess(command,0,'','')
            out,err=io.StringIO(),io.StringIO()
            env={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}
            with patch.dict(os.environ,env),patch.object(ops,'ROOT',root),patch.object(ops,'identity'),\
                 patch.object(ops,'aws',side_effect=fake_aws),patch.object(ops,'aws_cli',side_effect=fake_cli),\
                 patch.object(ops,'output',side_effect=lambda stack,key,**kw:key),\
                 patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=current),\
                 patch.object(ops.subprocess,'run',side_effect=fake_run),\
                 patch.object(ops.shutil,'which',return_value='/usr/local/bin/docker' if docker else None),\
                 contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                error=None
                try:
                    ops.secret_scan(SimpleNamespace(since=since,without_images=without_images))
                except ops.OpsError as e:
                    error=str(e)
            docker_dirs=[c for _,c in docker_calls if c]
            leftover=[c for c in docker_dirs if Path(c).exists()]
        printed=out.getvalue()+err.getvalue()
        import base64 as b64
        for value in [self.KTO,self.VERIFIER,'abc%2Bdef%2Fghi%3Djkl','abc%2bdef%2fghi%3djkl',b64.b64encode(self.KTO.encode()).decode()]:
            self.assertNotIn(value,printed,'a secret value was printed')
            for _,evidence in uploads:self.assertNotIn(value,json.dumps(evidence),'a secret value reached the evidence')
        return {'error':error,'out':printed,'uploads':uploads,'docker':docker_calls,'docker_dirs':docker_dirs,
                'leftover':leftover,'logs':log_calls}
    def test_a_clean_staging_is_scanned_everywhere_and_recorded(self):
        r=self.run_scan()
        self.assertIsNone(r['error'],r['out'])
        key,evidence=r['uploads'][0]
        self.assertTrue(key.startswith('s3://b/evidence/secret-exposure/v0.1.0-rc.9/'),key)
        self.assertEqual('clean',evidence['verdict'])
        self.assertEqual([],evidence['partialBecause'])
        self.assertEqual(1,evidence['webBundleFiles'])
        # The whole retention, not from deployedAt: that instant is recorded after the tasks had started and logged.
        self.assertEqual('retention',evidence['logs']['since'])
        self.assertTrue(r['logs'] and all('--start-time' not in c for c in r['logs']))
        self.assertEqual({'NullnullStgPlatform-ApiLogs1':1,'NullnullStgPlatform-AiLogs1':0,'NullnullStgPlatform-MigrationLogs1':0},
                         evidence['logs']['eventsByGroup'])
        self.assertEqual({'api':'sha256:'+'a'*64,'ai':'sha256:'+'b'*64},evidence['images'])
        self.assertEqual(0,evidence['blobsNotExpanded'])
        # Per image: the gzip layer and the xz file inflated; the boot jar, the library jar and the suffix-less zip opened.
        self.assertEqual(4,evidence['blobsInflated'])
        self.assertEqual(6,evidence['archivesExpanded'])
        self.assertEqual(['KTO_SERVICE_KEY','KTO_SERVICE_KEY_BASE64','KTO_SERVICE_KEY_JSON_ESCAPED','KTO_SERVICE_KEY_URLENCODED',
                          'KTO_SERVICE_KEY_URLENCODED_LOWER','VERIFIER_TOKEN','VERIFIER_TOKEN_BASE64'],evidence['variables'])
        self.assertIn('secret_exposure=clean release=v0.1.0-rc.9',r['out'])
    def test_the_ecr_login_lives_only_in_a_config_made_for_the_scan(self):
        r=self.run_scan()
        self.assertIsNone(r['error'],r['out'])
        commands=[c for c,_ in r['docker']]
        self.assertTrue(all(cfg for _,cfg in r['docker']),'every docker call runs on the scan-only config')
        self.assertEqual(1,len(set(r['docker_dirs'])))
        self.assertIn('logout',[c[1] for c in commands])
        self.assertEqual([],r['leftover'],'the scan-only config is removed')
    def test_the_key_as_logs_would_hold_it_is_found(self):
        import base64 as b64
        for line,variable in [('GET /B551011/KorService2?serviceKey=abc%2Bdef%2Fghi%3Djkl&_type=json','KTO_SERVICE_KEY_URLENCODED'),
                              ('serviceKey=abc%2bdef%2fghi%3djkl','KTO_SERVICE_KEY_URLENCODED_LOWER'),
                              ('{"url":"abc+def\\/ghi=jkl"}','KTO_SERVICE_KEY_JSON_ESCAPED'),
                              ('Authorization: '+b64.b64encode(self.KTO.encode()).decode(),'KTO_SERVICE_KEY_BASE64'),
                              ('key='+self.KTO,'KTO_SERVICE_KEY')]:
            with self.subTest(variable=variable):
                r=self.run_scan(logs={'NullnullStgPlatform-ApiLogs1':['Started',line]})
                self.assertIn('secret-exposure-leaked',r['error'] or '')
                evidence=r['uploads'][0][1]
                self.assertEqual('leaked',evidence['verdict'])
                self.assertEqual([variable],list(evidence['leaked']))
                self.assertIn(f'secret_exposure_leak variable={variable}',r['out'])
    def test_a_key_compressed_or_archived_inside_a_layer_is_found(self):
        # A plain file in the gzip layer, the boot jar, a jar in it, a zip with no suffix, and an xz file.
        for kw,where in [({'plain_text':b'KTO_SERVICE_KEY='+KTO_BYTES},'(gzip)'),
                         ({'jar_text':b'nullnull.kto.service-key='+KTO_BYTES},'!BOOT-INF/classes/application.yaml'),
                         ({'nested_text':b'key='+KTO_BYTES},'!BOOT-INF/lib/library.jar!library.properties'),
                         ({'odd_zip_text':b'key='+KTO_BYTES},'opt/bundle.dat!inside.txt'),
                         ({'xz_text':b'key='+KTO_BYTES},'usr/share/doc/notes(xz)')]:
            with self.subTest(where=where):
                r=self.run_scan(image=kw)
                self.assertIn('secret-exposure-leaked',r['error'] or '')
                places=r['uploads'][0][1]['leaked']['KTO_SERVICE_KEY']
                self.assertTrue(any(p.endswith(where) for p in places),places)
    def test_a_token_in_any_recorded_releases_bundle_is_found(self):
        # The web deployment never prunes: an old release's hashed files are still served.
        for kw in [{'web':b'<script>const t="'+self.VERIFIER.encode()+b'"</script>'},
                   {'old_web':b'<script>const t="'+self.VERIFIER.encode()+b'"</script>'}]:
            with self.subTest(which=list(kw)[0]):
                r=self.run_scan(**kw)
                self.assertIn('secret-exposure-leaked',r['error'] or '')
                self.assertEqual(['VERIFIER_TOKEN'],list(r['uploads'][0][1]['leaked']))
    def test_what_was_not_scanned_makes_the_verdict_partial(self):
        r=self.run_scan(task_secrets=('KTO_SERVICE_KEY','SPRING_DATASOURCE_PASSWORD','NULLNULL_CURSOR_SECRET'))
        self.assertIsNone(r['error'],r['out'])
        evidence=r['uploads'][0][1]
        self.assertEqual('clean-partial',evidence['verdict'])
        self.assertEqual(['NULLNULL_CURSOR_SECRET','SPRING_DATASOURCE_PASSWORD'],evidence['taskSecretsNotScanned'])
        self.assertIn('secret_exposure=clean-partial',r['out'])
        r=self.run_scan(without_images=True)
        evidence=r['uploads'][0][1]
        self.assertEqual('clean-partial',evidence['verdict'])
        self.assertEqual(['images not scanned'],evidence['partialBecause'])
        self.assertEqual([],r['docker'])
        # A compressed blob it could not open could hold anything: counted, and never clean.
        r=self.run_scan(image={'broken':True})
        evidence=r['uploads'][0][1]
        self.assertEqual('clean-partial',evidence['verdict'])
        self.assertEqual(2,evidence['blobsNotExpanded'])
        self.assertEqual(['2 blobs not expanded'],evidence['partialBecause'])
    def test_a_scan_that_could_not_read_what_it_claims_stops_without_a_verdict(self):
        for kw,reason in [({'logs':{}},'no-log-events-to-scan'),
                          ({'unrecorded':True},'deployed-release-not-recorded'),
                          ({'docker':False},'docker-required-for-image-scan')]:
            with self.subTest(reason=reason):
                r=self.run_scan(**kw)
                self.assertIn(reason,r['error'] or '')
                self.assertEqual([],r['uploads'])
    def test_since_narrows_the_logs_and_says_so(self):
        r=self.run_scan(since='2026-09-18T00:00:00+00:00')
        self.assertIsNone(r['error'],r['out'])
        self.assertTrue(all('--start-time' in c for c in r['logs']))
        self.assertEqual('2026-09-18T00:00:00+00:00',r['uploads'][0][1]['logs']['since'])

KTO_BYTES=SecretScanRegressions.KTO.encode()

class CoverClassificationRegressions(unittest.TestCase):
    """#183: a changed web bundle is an app release; a changed cover photo reaches the infra reviewer."""
    def template(self, bundle_key, cover_key):
        deployment=lambda prefix,key:{'Type':'Custom::CDKBucketDeployment','Properties':dict(
            {'SourceObjectKeys':[key],'DestinationBucketName':{'Ref':'Web'}},**({'DestinationBucketKeyPrefix':prefix} if prefix else {}))}
        return {'Resources':{'WebRelease':deployment(None,bundle_key),'CuratedCovers':deployment('covers/',cover_key)}}
    def test_only_the_web_bundle_is_masked(self):
        base=ops.normalize_template(self.template('bundle-1.zip','covers-1.zip'))
        self.assertEqual(base,ops.normalize_template(self.template('bundle-2.zip','covers-1.zip')))
        self.assertNotEqual(base,ops.normalize_template(self.template('bundle-1.zip','covers-2.zip')))

class PlanStagesCoversRegressions(unittest.TestCase):
    """#183: a release plan carries the cover photos into its assembly - only them, and never none."""
    def run_plan(self, covers):
        import contextlib, io
        from types import SimpleNamespace
        calls=[]
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            folder=root/'docs/contest/covers';folder.mkdir(parents=True)
            for name,data in covers.items():(folder/name).write_bytes(data)
            web=root/'web';web.mkdir();(web/'index.html').write_text('synthetic')
            cost=root/'cost.txt';cost.write_text('estimate')
            (root/'infra').mkdir();(root/'infra/package-lock.json').write_text('{}')
            args=SimpleNamespace(action='deploy',manifest='m.json',web_dir=str(web),days=7,estimated_total=150,
                                 cost_basis=str(cost))
            out=io.StringIO()
            with patch.dict(os.environ,{'NULLNULL_AWS_ACCOUNT_ID':'1'*12}),patch.object(ops,'ROOT',root),\
                 patch.object(ops,'validate_manifest',return_value={'kind':'release'}),patch.object(ops,'check_artifacts'),\
                 patch.object(ops,'run'),patch.object(ops,'cdk',side_effect=lambda command,**kw:calls.append(command)),\
                 patch.object(ops,'verifier_hash',return_value='c'*64),patch.object(ops,'tree_digest',return_value='t'),\
                 contextlib.redirect_stdout(out):
                error=None
                try:
                    ops.plan(args)
                except ops.OpsError as e:
                    error=str(e)
            staged={p.name:p.read_bytes() for p in (root/'.artifacts/aws/plans').glob('*/covers/*')}
        return {'error':error,'cdk':calls,'staged':staged,'out':out.getvalue()}
    def test_the_jpgs_and_nothing_else_reach_the_assembly_the_owner_approves(self):
        r=self.run_plan({'01-a.jpg':b'first','02-b.jpg':b'second','README.md':b'not content'})
        self.assertIsNone(r['error'],r['out'])
        self.assertEqual({'01-a.jpg':b'first','02-b.jpg':b'second'},r['staged'])
        synth=[str(a) for a in r['cdk'][0]]
        covers=[a for a in synth if a.startswith('coversDirectory=')]
        self.assertEqual(1,len(covers),synth)
        self.assertTrue(covers[0].endswith('/covers'),covers)
        self.assertIn('covers=01-a.jpg,02-b.jpg',r['out'])
    def test_a_plan_with_no_cover_photo_stops_before_synth(self):
        r=self.run_plan({'README.md':b'not content'})
        self.assertIn('cover-photos-missing',r['error'] or '')
        self.assertEqual([],r['cdk'])

class KtoCallInventoryRegressions(unittest.TestCase):
    """kto-call-inventory: the deployed release's KTO operation list, as the file check_submission_inventory reads."""
    DIGEST='sha256:'+'a'*64
    RELEASE='v0.1.0-rc.9'
    # Verbatim what KtoCallInventoryMain.render prints for a deployed environment with two operations.
    LINES=['kto_inventory target=postgresql://db.example.internal:5432/nullnull environment=staging release=v0.1.0-rc.9',
           'kto_operation source=KTO_KOR_SERVICE_2 endpoint=KOR_SERVICE_2_DETAIL_COMMON_2 calls=12 first=2026-09-19T05:29:12.345678Z last=2026-09-20T01:02:03Z',
           'kto_operation source=KTO_CONCENTRATION_FORECAST endpoint=TATS_CNCTR_RATE_LIST calls=8 first=2026-09-19T06:00:00Z last=2026-09-20T02:00:00.5Z',
           'kto_inventory_excluded rejected=0 replay=1',
           'kto_inventory operations=2 counts_as_evidence=true']
    MANIFEST={'releaseVersion':'v0.1.0-rc.9','gitSha':'a'*40,'apiImageDigest':'sha256:'+'a'*64,'aiImageDigest':'sha256:'+'b'*64}
    def run_inventory(self, lines=None, pages=None, image=None, release=None, others=(), endless=False):
        import contextlib, io, tarfile as tf
        from types import SimpleNamespace
        pages=pages if pages is not None else [['Starting NullnullApiApplication']+(self.LINES if lines is None else lines)]
        calls,uploads=[],[]
        def fake(service,operation,**kw):
            calls.append((service,operation,kw))
            if (service,operation)==('rds','describe-db-instances'):
                return {'DBInstances':[{'Endpoint':{'Address':OperationsTargetRegressions.HOST,'Port':5432},'DBName':'nullnull'}]}
            if (service,operation)==('ecs','describe-task-definition'):
                return {'taskDefinition':{'containerDefinitions':[{'name':'ops','image':image or '1.dkr.ecr/nullnull-api@'+self.DIGEST,
                        'environment':[{'name':'APP_RELEASE_VERSION','value':release or self.RELEASE}]}]}}
            if (service,operation)==('ecs','run-task'): return {'tasks':[{'taskArn':'arn:aws:ecs:r:a:task/c/abc123'}]}
            if (service,operation)==('logs','get-log-events'):
                if endless:return {'events':[],'nextForwardToken':'f/'+str(int(kw.get('nextToken','f/0').split('/')[1])+1)}
                # As CloudWatch answers: a token past the last page returns no events and that same token again.
                n=int(kw.get('nextToken','f/0').split('/')[1])
                if n>=len(pages):return {'events':[],'nextForwardToken':f'f/{n}'}
                return {'events':[{'message':m} for m in pages[n]],'nextForwardToken':f'f/{n+1}'}
            raise AssertionError((service,operation))
        plans={f'releases/{"c"*64}/plan.tgz':self.MANIFEST,
               **{f'releases/{str(n)*64}/plan.tgz':m for n,m in enumerate(others)}}
        def fake_cli(args,**kw):
            args=[str(a) for a in args]
            if args[:2]==['s3api','list-objects-v2']:
                return subprocess.CompletedProcess(args,0,json.dumps({'Contents':[{'Key':k} for k in plans]}),'')
            if args[:2]==['s3','cp'] and args[2].startswith('s3://'):
                data=json.dumps(plans[args[2][len('s3://b/'):]]).encode()
                with tf.open(args[3],'w:gz') as tar:
                    info=tf.TarInfo('release.json');info.size=len(data);tar.addfile(info,io.BytesIO(data))
                return subprocess.CompletedProcess(args,0,'','')
            uploads.append((args[3],Path(args[2]).read_text()));return subprocess.CompletedProcess(args,0,'','')
        record={'releaseVersion':self.RELEASE,'gitSha':'a'*40,'planKey':f'releases/{"c"*64}/plan.tgz',
                'releaseManifest':{**self.MANIFEST,'apiImageDigest':self.DIGEST}}
        env={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12,
             ops.OPERATIONS_TARGET:OperationsTargetRegressions.TARGET}
        out=io.StringIO()
        with tempfile.TemporaryDirectory() as d:
            with patch.dict(os.environ,env),patch.object(ops,'identity'),patch.object(ops,'aws',side_effect=fake),\
                 patch.object(ops,'output',side_effect=lambda stack,key,**kw:'s-a,s-b' if key=='AppSubnetIds' else key),\
                 patch.object(ops,'DeploymentLock',OperationsTargetRegressions.Lock),patch.object(ops,'wait_task'),\
                 patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=record),\
                 patch.object(ops,'ROOT',Path(d)),patch.object(ops,'aws_cli',side_effect=fake_cli),contextlib.redirect_stdout(out):
                error=None
                try:
                    ops.ops_task(SimpleNamespace(task='kto-call-inventory',content_id=None,content_type_id=None,place_id=None,
                                                 owner_approval=None,places=None,plan_file=None,approved_plan_sha256=None))
                except ops.OpsError as e:
                    error=str(e)
                kept=sorted((Path(d)/'.artifacts/aws/evidence').glob('kto-inventory-*.txt'))
                kept=kept[0].read_text() if kept else None
        run=[kw for s_,o,kw in calls if (s_,o)==('ecs','run-task')]
        return {'error':error,'out':out.getvalue(),'run':run,'kept':kept,'uploads':uploads,'calls':[(s_,o) for s_,o,_ in calls]}
    def test_the_deployed_releases_inventory_is_kept_as_the_checker_reads_it(self):
        import importlib.util as iu
        r=self.run_inventory()
        self.assertIsNone(r['error'],r['out'])
        env={e['name']:e['value'] for e in r['run'][0]['overrides']['containerOverrides'][0]['environment']}
        self.assertEqual('io.nullnull.crowd.infrastructure.audit.KtoCallInventoryMain',env['LOADER_MAIN'])
        # The release is the deployed one, set by the operator: no input can name another.
        self.assertEqual(self.RELEASE,env['NULLNULL_INVENTORY_RELEASE'])
        # It calls no provider, so it carries no KTO approval.
        self.assertFalse([n for n in env if n.startswith('NULLNULL_KTO_')],env)
        self.assertEqual('\n'.join(self.LINES)+'\n',r['kept'])
        key,uploaded=r['uploads'][0]
        self.assertTrue(key.startswith(f's3://b/evidence/kto-inventory/{self.RELEASE}/kto-inventory-{self.RELEASE}-'),key)
        self.assertEqual(r['kept'],uploaded)
        # The consumer's own patterns find the release and both operations in the kept file.
        spec=iu.spec_from_file_location('check_submission_inventory',ROOT/'scripts/check_submission_inventory.py')
        checker=iu.module_from_spec(spec);spec.loader.exec_module(checker)
        self.assertEqual(self.RELEASE,checker.HEADER_LINE.search(r['kept']).group(1))
        self.assertEqual({('KTO_KOR_SERVICE_2','KOR_SERVICE_2_DETAIL_COMMON_2'),('KTO_CONCENTRATION_FORECAST','TATS_CNCTR_RATE_LIST')},
                         set(checker.OPERATION_LINE.findall(r['kept'])))
        self.assertIn('counts_as_evidence=true',r['kept'])
        self.assertIn(f'kto_inventory_file=',r['out'])
        self.assertIn('operations=2 counts_as_evidence=true',r['out'])
    def test_lines_after_the_first_page_of_startup_are_read(self):
        r=self.run_inventory(pages=[['startup line']*500,['more startup']+self.LINES])
        self.assertIsNone(r['error'],r['out'])
        self.assertEqual('\n'.join(self.LINES)+'\n',r['kept'])
        self.assertEqual(3,r['calls'].count(('logs','get-log-events')))  # two pages, then the empty one that ends it
    def test_an_inventory_that_is_not_whole_or_not_this_releases_records_nothing(self):
        other=[self.LINES[0].replace('rc.9','rc.8')]+self.LINES[1:]
        for lines,reason in [([],'inventory-header-missing'),
                             (other,'inventory-not-for-the-deployed-release'),
                             (self.LINES[:-1],'inventory-incomplete'),
                             # A line lost between the main and the log: the total no longer counts what was read.
                             ([self.LINES[0],self.LINES[1],self.LINES[3],self.LINES[4]],'inventory-incomplete')]:
            with self.subTest(reason=reason,lines=len(lines)):
                r=self.run_inventory(lines=lines)
                self.assertIn(reason,r['error'] or '')
                self.assertIsNone(r['kept']);self.assertEqual([],r['uploads'])
    def test_it_runs_only_on_the_recorded_releases_image(self):
        for kw,reason in [({'image':'1.dkr.ecr/nullnull-api@sha256:'+'b'*64},'ops-image-not-the-deployed-release'),
                          ({'release':'v0.1.0-rc.8'},'ops-definition-not-the-deployed-release')]:
            with self.subTest(reason=reason):
                r=self.run_inventory(**kw)
                self.assertIn(reason,r['error'] or '')
                self.assertEqual([],r['run'])
    def test_a_version_another_artifact_deployed_under_is_refused(self):
        # Same version, another build: the audit would add both artifacts' calls into one list.
        for field in ['gitSha','apiImageDigest','aiImageDigest']:
            with self.subTest(field=field):
                r=self.run_inventory(others=[{**self.MANIFEST,field:self.MANIFEST[field][:-1]+'f'}])
                self.assertIn('release-version-reused-by-another-artifact',r['error'] or '')
                self.assertEqual([],r['run'])
        # A rollback records the same manifest again under a new plan: the same artifact, allowed. Another version, too.
        r=self.run_inventory(others=[dict(self.MANIFEST),{**self.MANIFEST,'releaseVersion':'v0.1.0-rc.8','gitSha':'e'*40}])
        self.assertIsNone(r['error'],r['out'])
    def test_a_log_stream_that_never_settles_is_not_read_as_complete(self):
        r=self.run_inventory(endless=True)
        self.assertIn('task-log-not-fully-read',r['error'] or '')
        self.assertIsNone(r['kept'])
    def test_the_inventory_lines_pass_and_nothing_that_carries_more(self):
        allowed=self.LINES+['kto_inventory target=unknown environment=unset release=v0.1.0',
                            'kto_inventory operations=0 counts_as_evidence=false reason=environment-not-deployed']
        refused=['kto_inventory target=postgresql://user:secret@db:5432/nullnull environment=staging release=v0.1.0-rc.9',
                 self.LINES[1]+' serviceKey=abc',
                 'kto_operation source=KTO_KOR_SERVICE_2 endpoint=detail common calls=1 first=2026-09-19T05:29:12Z last=2026-09-19T05:29:12Z',
                 'kto_inventory release=v0.1.0-rc.9 title=경복궁',
                 'kto_inventory operations=2 counts_as_evidence=true extra']
        for line in allowed: self.assertTrue(ops.OPS_LOG_LINE.match(line),line)
        for line in refused: self.assertFalse(ops.OPS_LOG_LINE.match(line),line)

class EdgeRegressions(unittest.TestCase):
    """edge opens or closes the public API of the deployed release by redeploying WebEdge alone from its own plan."""
    PLAN_SHA='p'*64
    RECORD={'releaseVersion':'v0.1.0-rc.2','planSha256':'p'*64,'gitSha':'a'*40}
    DATA={'account':'1'*12,'action':'deploy','verifierTokenSha256':'c'*64,'assemblySha256':'x'}
    OPEN=(200,'application/json',None);CLOSED=(503,'application/problem+json',None)
    def run_edge(self, state, execute, record=None, live='false', answers=(OPEN,), env=None):
        import contextlib, io
        from types import SimpleNamespace
        events=[]
        class Lock:
            def __enter__(inner): events.append('lock-enter'); return inner
            def __exit__(inner,*a): events.append('lock-exit'); return False
            def mutating(inner): events.append('mutating')
        base={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12,
              'NULLNULL_VERIFIER_TOKEN':'t'*43}
        def current(*a):
            events.append('read-current'); return self.RECORD if record is None else record
        def traffic():
            events.append('read-traffic')
            if live=='busy': raise ops.OpsError('webedge-stack-busy')
            return live
        out=io.StringIO()
        with patch.dict(os.environ,{**base,**(env or {})}),patch.object(ops,'verify_deployed_plan',return_value=self.DATA),\
             patch.object(ops,'identity'),patch.object(ops,'release_bucket',return_value='b'),\
             patch.object(ops,'read_current_release',side_effect=current),patch.object(ops,'edge_traffic_enabled',side_effect=traffic),\
             patch.object(ops,'output',side_effect=lambda stack,key,*a,**kw:{'PublicUrl':'https://d.example','WebAclArn':'arn:waf'}[key]),\
             patch.object(ops,'run',side_effect=lambda cmd,**kw:events.append('run:'+Path(str(cmd[1])).name)) as run,\
             patch.object(ops,'deploy_approved_stack',side_effect=lambda *a,**kw:events.append('deploy:'+a[2])) as deploy,\
             patch.object(ops,'DeploymentLock',Lock),\
             patch.object(ops,'public_health_answers',return_value=iter(answers)),contextlib.redirect_stdout(out):
            if env and env.get('NULLNULL_VERIFIER_TOKEN')=='':os.environ.pop('NULLNULL_VERIFIER_TOKEN')
            error=None
            try:
                ops.edge(SimpleNamespace(state=state,execute=execute,plan='/plans/p/plan.json',approved_plan_sha256=self.PLAN_SHA))
            except ops.OpsError as e:
                error=str(e)
        return error,run,deploy,out.getvalue(),events
    def test_planning_to_open_checks_under_the_lock_runs_the_cd_checks_and_writes_nothing(self):
        error,run,deploy,out,events=self.run_edge('open',False)
        self.assertIsNone(error,out)
        # Every read that decides the deploy happens under the lock (a release cannot land in between).
        self.assertEqual(['lock-enter','read-current','read-traffic','run:staging-smoke.sh','run:staging-flows.mjs','lock-exit'],events)
        deploy.assert_not_called()
        self.assertIn('edge_action=plan aws_writes=0',out)
        self.assertIn('default deploy and rollback set TrafficEnabled=false; preserve-open deploy retains it',out)
    def test_opening_redeploys_webedge_alone_under_the_lock_and_waits_for_the_apis_answer(self):
        error,run,deploy,out,events=self.run_edge('open',True,answers=(self.CLOSED,(503,'text/html',None),self.OPEN))
        self.assertIsNone(error,out)
        self.assertEqual(['lock-enter','read-current','read-traffic','run:staging-smoke.sh','run:staging-flows.mjs','deploy:WebEdge','lock-exit'],events)
        deploy.assert_called_once()
        self.assertEqual(['NullnullStgWebEdge:GlobalWebAclArn=arn:waf','NullnullStgWebEdge:TrafficEnabled=true',
                          'NullnullStgWebEdge:VerifierTokenSha256='+'c'*64],deploy.call_args.args[4])
        self.assertIn('edge=open release=v0.1.0-rc.2 public_health_status=200 content_type=application/json',out)
    def test_closing_needs_no_precheck_and_an_alb_503_is_not_a_closed_gate(self):
        error,run,deploy,out,events=self.run_edge('closed',True,live='true',env={'NULLNULL_VERIFIER_TOKEN':''},
                                                  answers=(self.OPEN,(503,'text/html',None),self.CLOSED))
        self.assertIsNone(error,out)
        self.assertEqual(['lock-enter','read-current','read-traffic','deploy:WebEdge','lock-exit'],events)
        self.assertIn('NullnullStgWebEdge:TrafficEnabled=false',deploy.call_args.args[4])
        self.assertIn('edge=closed release=v0.1.0-rc.2 public_health_status=503 content_type=application/problem+json',out)
    def test_an_edge_already_in_the_asked_state_is_only_verified(self):
        error,run,deploy,out,events=self.run_edge('open',True,live='true')
        self.assertIsNone(error,out)
        self.assertNotIn('run:staging-flows.mjs',events);deploy.assert_not_called()
        self.assertIn('edge_action=none reason=already-open',out)
        self.assertIn('edge=open release=v0.1.0-rc.2 public_health_status=200',out)
    def test_what_must_not_open_the_edge_is_refused_before_any_deploy(self):
        other={**self.RECORD,'planSha256':'q'*64}
        for kw,reason in [({'record':other},'plan-is-not-the-deployed-release'),
                          ({'env':{'NULLNULL_VERIFIER_TOKEN':''}},'edge-open-requires-verifier-token'),
                          ({'live':'busy'},'webedge-stack-busy')]:
            with self.subTest(reason=reason):
                error,run,deploy,out,events=self.run_edge('open',True,**kw)
                self.assertIn(reason,error or '')
                deploy.assert_not_called();self.assertNotIn('run:staging-flows.mjs',events)
        error,run,deploy,out,events=self.run_edge('open',True,answers=(self.CLOSED,(None,None,'URLError')))
        self.assertIn('edge-not-open-after-deploy-last-None-URLError',error or '')
        with patch.dict(os.environ,AuthModeRegressions.AMBIENT):
            with self.assertRaisesRegex(ops.OpsError,'edge-is-local-only'):
                ops.edge(__import__('types').SimpleNamespace(state='open',execute=True,plan='x',approved_plan_sha256='y'))
    def test_the_live_traffic_parameter_is_read_only_from_a_settled_stack(self):
        def stack(status, value):
            params=[] if value is None else [{'ParameterKey':'TrafficEnabled','ParameterValue':value},
                                              {'ParameterKey':'VerifierTokenSha256','ParameterValue':'c'*64}]
            return {'Stacks':[{'StackStatus':status,'Parameters':params}]}
        with patch.object(ops,'aws',return_value=stack('UPDATE_COMPLETE','true')):
            self.assertEqual('true',ops.edge_traffic_enabled())
        for answer,reason in [(stack('UPDATE_IN_PROGRESS','false'),'webedge-stack-busy'),
                              (stack('UPDATE_COMPLETE',None),'webedge-traffic-parameter-unreadable')]:
            with self.subTest(reason=reason),patch.object(ops,'aws',return_value=answer):
                with self.assertRaisesRegex(ops.OpsError,reason):ops.edge_traffic_enabled()
    def deployed_plan(self, root, **changes):
        import datetime as dt
        (root/'assembly').mkdir(exist_ok=True);(root/'assembly'/'x.json').write_text('{}')
        (root/'release.json').write_text('{}');(root/'cost-basis.txt').write_text('basis')
        now=dt.datetime.now(dt.timezone.utc)
        data={'version':1,'region':ops.REGION,'account':'1'*12,'action':'deploy',
              'createdAt':(now-dt.timedelta(days=3)).isoformat(),'expiresAt':(now+dt.timedelta(days=5)).isoformat(),
              'releaseSha256':ops.digest(root/'release.json'),'assemblySha256':ops.tree_digest(root/'assembly'),
              'costBasisSha256':ops.digest(root/'cost-basis.txt'),'toolchainSha256':ops.digest(ROOT/'infra/package-lock.json'),
              'verifierTokenSha256':'c'*64,'estimateUsd':80,'reserveUsd':20,**changes}
        (root/'plan.json').write_text(json.dumps(data))
        return root/'plan.json',ops.digest(root/'plan.json')
    def test_the_deployed_plan_is_usable_days_later_but_every_hash_still_has_to_hold(self):
        with tempfile.TemporaryDirectory() as d,patch.dict(os.environ,{'NULLNULL_AWS_ACCOUNT_ID':'1'*12}):
            root=Path(d)
            plan,sha=self.deployed_plan(root)
            self.assertEqual('deploy',ops.verify_deployed_plan(plan,sha)['action'])
            # The contrast: the same plan, three days old but inside its window, is refused by verify_plan's
            # freshness rule alone.
            with self.assertRaisesRegex(ops.OpsError,'plan-older-than-24-hours'):ops.verify_plan(plan,sha)
            for changes,reason in [({'account':'2'*12},'plan-account-mismatch'),({'action':'bootstrap'},'edge-requires-a-release-plan'),
                                   ({'verifierTokenSha256':'v'*64},'invalid-verifier-hash'),({'region':'us-east-1'},'invalid-plan')]:
                with self.subTest(reason=reason):
                    plan,sha=self.deployed_plan(root,**changes)
                    with self.assertRaisesRegex(ops.OpsError,reason):ops.verify_deployed_plan(plan,sha)
            for name,reason in [('release.json','release-changed'),('cost-basis.txt','cost-basis-changed'),
                                ('assembly/x.json','assembly-changed')]:
                with self.subTest(reason=reason):
                    plan,sha=self.deployed_plan(root)
                    (root/name).write_text('{"changed":true}')
                    with self.assertRaisesRegex(ops.OpsError,reason):ops.verify_deployed_plan(plan,sha)
            plan,sha=self.deployed_plan(root)
            with self.assertRaisesRegex(ops.OpsError,'reviewed-plan-does-not-match'):ops.verify_deployed_plan(plan,'0'*64)
            other=Path(d)/'other-root';(other/'infra').mkdir(parents=True);(other/'infra'/'package-lock.json').write_text('{}')
            with patch.object(ops,'ROOT',other),self.assertRaisesRegex(ops.OpsError,'toolchain-changed'):
                ops.verify_deployed_plan(plan,sha)

class WaitTaskRegressions(unittest.TestCase):
    """The executed-image comparison itself, which every ops_task test patches away with a Mock."""
    class Lock:
        def check(self): pass
    def wait(self, ran_digest, expected):
        stopped={'tasks':[{'lastStatus':'STOPPED','stopCode':'EssentialContainerExited',
                           'containers':[{'name':'ops','exitCode':0,'imageDigest':ran_digest}]}]}
        definition={'containerDefinitions':[{'name':'ops','essential':True}]}
        with patch.object(ops,'aws',return_value=stopped):
            return ops.wait_task('c','arn',self.Lock(),definition,'ops',expected)
    def test_a_task_that_ran_another_image_is_refused_after_it_stops(self):
        digest='sha256:'+'a'*64
        with self.assertRaisesRegex(ops.OpsError,'executed-image-mismatch'):self.wait('sha256:'+'b'*64,digest)
        self.assertEqual('STOPPED',self.wait(digest,digest)['lastStatus'])
        self.assertEqual('STOPPED',self.wait('sha256:'+'b'*64,None)['lastStatus'])

class WithdrawPostTaskRegressions(unittest.TestCase):
    """BA-082-T16's operator task: withdraw-post takes one published post back (PostWithdrawMain).

    A withdrawal is only as good as the evidence that it happened to the post the owner named, so the task
    succeeds on exactly one line naming that post - not on a zero exit code and not on any line at all."""
    POST='0192f3a4-5b6c-7d8e-9f01-23456789abcd'
    OTHER='0192f3a4-5b6c-7d8e-9f01-23456789abce'
    DIGEST='sha256:'+'a'*64
    RECORD={'releaseVersion':'v0.1.0-rc.2','gitSha':'a'*40,'releaseManifest':{'apiImageDigest':'sha256:'+'a'*64}}
    # What PostWithdrawMainTest makes PostWithdrawMain print, verbatim.
    JAVA_LINES=['post_withdrawn post=0192f3a4-5b6c-7d8e-9f01-23456789abcd outcome=WITHDRAWN cover=DELETED versions=2',
                'post_withdrawn post=0192f3a4-5b6c-7d8e-9f01-23456789abcd outcome=ALREADY_HIDDEN cover=ALREADY_ABSENT versions=0',
                'post_withdrawn post=0192f3a4-5b6c-7d8e-9f01-23456789abcd outcome=WITHDRAWN cover=NOT_USER_UPLOAD versions=0',
                'post_withdraw_failed reason=NOT_FOUND','post_withdraw_failed reason=NOT_PUBLISHED',
                'post_withdraw_failed reason=APPROVAL_NOT_SET','post_withdraw_failed reason=POST_ID_INVALID',
                'post_withdraw_failed reason=IllegalStateException',
                'post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending']
    BASE={'NULLNULL_AWS_AUTH':'profile','AWS_PROFILE':'p','NULLNULL_AWS_ACCOUNT_ID':'1'*12}
    def args(self, **overrides):
        from types import SimpleNamespace
        base={'task':'withdraw-post','post_id':self.POST,'owner_approval':'owner approved in session','plan_file':None}
        return SimpleNamespace(**{**base,**overrides})
    def run_withdraw(self, log, ops_image=None, record=RECORD):
        import contextlib, io
        calls=[]
        def fake(service,operation,**kw):
            calls.append((service,operation,kw))
            if (service,operation)==('rds','describe-db-instances'):
                return {'DBInstances':[{'Endpoint':{'Address':OperationsTargetRegressions.HOST,'Port':5432},'DBName':'nullnull'}]}
            if (service,operation)==('ecs','describe-task-definition'):
                return {'taskDefinition':{'containerDefinitions':[{'name':'ops',
                        'image':ops_image or '1.dkr.ecr/nullnull-api@'+self.DIGEST,
                        'environment':[{'name':'APP_RELEASE_VERSION','value':'v0.1.0-rc.2'}]}]}}
            if (service,operation)==('ecs','run-task'): return {'tasks':[{'taskArn':'arn:aws:ecs:r:a:task/c/abc123'}]}
            if (service,operation)==('logs','get-log-events'): return {'events':[{'message':m} for m in log]}
            raise AssertionError((service,operation))
        env={**self.BASE,'NULLNULL_POST_WITHDRAW_APPROVED':'true',ops.OPERATIONS_TARGET:OperationsTargetRegressions.TARGET}
        out=io.StringIO()
        with patch.dict(os.environ,env),patch.object(ops,'identity'),patch.object(ops,'aws',side_effect=fake),\
             patch.object(ops,'output',side_effect=lambda stack,key,**kw:'s-a,s-b' if key=='AppSubnetIds' else key),\
             patch.object(ops,'DeploymentLock',OperationsTargetRegressions.Lock),patch.object(ops,'wait_task') as wait,\
             patch.object(ops,'release_bucket',return_value='b'),patch.object(ops,'read_current_release',return_value=record),\
             contextlib.redirect_stdout(out):
            error=None
            try:
                ops.ops_task(self.args())
            except ops.OpsError as e:
                error=str(e)
        self.wait=wait
        return error,calls,out.getvalue()
    # 'Before the task': ops_task reads the caller's identity (STS) before any input is checked, for every task, so
    # these tests patch identity() and claim only that nothing touches RDS, ECS or the logs.
    def test_the_callers_approval_and_a_record_are_required_before_the_task(self):
        with patch.dict(os.environ,self.BASE),patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
            os.environ.pop('NULLNULL_POST_WITHDRAW_APPROVED',None)
            with self.assertRaisesRegex(ops.OpsError,'nullnull-post-withdraw-approved-not-set-by-caller'):
                ops.ops_task(self.args())
            with patch.dict(os.environ,{'NULLNULL_POST_WITHDRAW_APPROVED':'true'}):
                for record in (None,'too short'):
                    with self.subTest(record=record),self.assertRaisesRegex(ops.OpsError,'owner-approval-record-required'):
                        ops.ops_task(self.args(owner_approval=record))
            aws.assert_not_called()
    def test_the_post_id_is_a_canonical_uuid_before_the_task(self):
        with patch.dict(os.environ,{**self.BASE,'NULLNULL_POST_WITHDRAW_APPROVED':'true'}),\
             patch.object(ops,'identity'),patch.object(ops,'aws') as aws:
            # The loose place_id shape would pass the first three; a post id is exactly one canonical UUID.
            for bad in ('','----','0192f3a4','0192f3a4-5b6c-7d8e-9f01-23456789abc',self.POST.upper(),self.POST+'0',
                        self.POST+"'; DELETE FROM posts; --",' '+self.POST,None):
                with self.subTest(post_id=bad),self.assertRaisesRegex(ops.OpsError,'invalid-post-id'):
                    ops.ops_task(self.args(post_id=bad))
            aws.assert_not_called()
    def test_the_post_and_the_approval_travel_to_the_deployed_release(self):
        success='post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=DELETED versions=2'
        error,calls,out=self.run_withdraw([success])
        self.assertIsNone(error)
        run=[kw for s,o,kw in calls if (s,o)==('ecs','run-task')]
        self.assertEqual(1,len(run))
        environment={e['name']:e['value'] for e in run[0]['overrides']['containerOverrides'][0]['environment']}
        self.assertEqual('io.nullnull.social.infrastructure.moderation.PostWithdrawMain',environment['LOADER_MAIN'])
        self.assertEqual(self.POST,environment['NULLNULL_WITHDRAW_POST_ID'])
        self.assertEqual('true',environment['NULLNULL_POST_WITHDRAW_APPROVED'])
        self.assertEqual(OperationsTargetRegressions.TARGET,environment[ops.OPERATIONS_TARGET])
        self.assertIn('owner_approval=owner approved in session',out)
        self.assertIn('ops_log '+success,out)
        self.assertNotIn('post_withdraw_residual=cover-object-not-deleted',out)
        self.assertIn('ops_task=withdraw-post result=succeeded',out)
        # The task that ran is checked against the deployed release's image, not only the definition that was named.
        self.assertEqual(self.DIGEST,self.wait.call_args.args[5])
    def test_a_rerun_that_finds_the_post_already_hidden_succeeds(self):
        error,_,out=self.run_withdraw(['post_withdrawn post='+self.POST+' outcome=ALREADY_HIDDEN cover=ALREADY_ABSENT versions=0'])
        self.assertIsNone(error)
        self.assertIn('ops_task=withdraw-post result=succeeded',out)
        error,_,out=self.run_withdraw(['post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=NOT_USER_UPLOAD versions=0'])
        self.assertIsNone(error)
        self.assertIn('ops_task=withdraw-post result=succeeded',out)
    def test_only_one_line_naming_the_requested_post_counts_as_a_withdrawal(self):
        named='post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=DELETED versions=2'
        for log in ([],['post_withdraw_failed reason=NOT_FOUND'],['post_withdraw_failed reason=NOT_PUBLISHED'],
                    ['post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending'],
                    ['post_withdrawn post='+self.OTHER+' outcome=WITHDRAWN cover=DELETED versions=2'],[named,named],
                    [named,'post_withdraw_failed reason=IllegalStateException'],
                    [named,'post_withdrawn post='+self.OTHER+' outcome=WITHDRAWN cover=DELETED versions=2'],
                    [named,named+' cover_url=https://private.example/x'],
                    ['post_withdrawn post='+self.POST+' outcome=WITHDRAWN'],
                    ['post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=ALREADY_ABSENT versions=2']):
            with self.subTest(log=log):
                error,_,out=self.run_withdraw(log)
                self.assertIn('post-not-withdrawn',error or '')
                self.assertNotIn('result=succeeded',out)
                self.assertNotIn('post_withdraw_residual',out)
                self.assertNotIn('private.example',out)
    def test_it_runs_only_on_the_deployed_release(self):
        error,calls,_=self.run_withdraw(['post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=DELETED versions=2'],
                                        ops_image='1.dkr.ecr/nullnull-api@sha256:'+'b'*64)
        self.assertIn('ops-image-not-the-deployed-release',error or '')
        self.assertNotIn(('ecs','run-task'),[(s,o) for s,o,_ in calls])
    def test_its_lines_pass_the_log_allowlist_and_nothing_richer_does(self):
        for line in self.JAVA_LINES:
            self.assertTrue(ops.OPS_LOG_LINE.match(line),line)
        for line in ['post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=DELETED versions=2 title=광화문 산책',
                     'post_withdrawn post='+self.POST+' outcome=DELETED',
                     'post_withdrawn post='+self.POST,
                     'post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=DELETED versions=2 cover_url=https://nullnull.test/x',
                     'post_withdrawn post='+self.POST+' outcome=WITHDRAWN cover=DELETED versions=-1',
                     'post_withdraw_failed reason=NOT_FOUND jdbc:postgresql://db:5432/nullnull',
                     'post_withdraw_failed reason=could not find post 0192f3a4']:
            self.assertFalse(ops.OPS_LOG_LINE.match(line),line)
