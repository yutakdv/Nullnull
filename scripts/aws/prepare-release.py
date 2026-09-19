#!/usr/bin/env python3
"""Produce release metadata from built web assets and already-built immutable image digests."""
import argparse
import hashlib
import re
from pathlib import Path
import subprocess
from staging_operator import ROOT, digest, image_tag, tree_digest, write_private, validate_manifest

def source_state():
    """("clean", None) when the tree is exactly HEAD, else ("overlay", sha256 over every uncommitted path).

    Computed from git, never passed in: an overlay build must not be able to present itself as a commit.
    """
    raw = subprocess.check_output(['git', 'status', '--porcelain=v1', '-z', '--untracked-files=all'], cwd=ROOT)
    entries = [e for e in raw.decode().split('\0') if e]
    if not entries:
        return 'clean', None
    paths, skip = [], False
    for entry in entries:
        if skip:
            skip = False
            continue
        status, path = entry[:2], entry[3:]
        if status[0] in 'RC':
            skip = True  # -z renames carry the original path as the next entry
        paths.append(path)
    global _OVERLAY_PATHS
    _OVERLAY_PATHS = sorted(set(paths))
    h = hashlib.sha256()
    for path in sorted(set(paths)):
        target = ROOT / path
        h.update(path.encode() + b'\0' + (digest(target).encode() if target.is_file() else b'deleted') + b'\n')
    return 'overlay', 'sha256:' + h.hexdigest()

_OVERLAY_PATHS = []
_REVISION = re.compile(r'current_revision\s*=\s*([0-9]+)')

def sql_statements(text):
    """Split on ';' outside quoted strings, dropping -- comments. Both carry semicolons in prose here:
    V012's header comment and its refresh_expectation literal each contain one."""
    statements, current, i, quoted = [], [], 0, False
    while i < len(text):
        c = text[i]
        if quoted:
            current.append(c)
            if c == "'":
                if text[i + 1:i + 2] == "'":
                    current.append("'"); i += 1
                else:
                    quoted = False
        elif c == "'":
            quoted = True; current.append(c)
        elif text.startswith('--', i):
            while i < len(text) and text[i] != '\n':
                i += 1
            continue
        elif c == ';':
            statements.append(''.join(current)); current = []
        else:
            current.append(c)
        i += 1
    statements.append(''.join(current))
    return statements

def catalog_version(migrations):
    """KTO_KOR_SERVICE_2:<revision> as the migrations leave it - the same string the API's
    CatalogVersion.current() reads back from source_registry at runtime.

    Derived, never typed: a release that passed a stale value would make apps/ai report a catalog
    version the database does not have. A statement that touches this source's revision in a shape
    this cannot read stops the release instead of being skipped.
    """
    revisions = []
    for path in migrations:
        for statement in sql_statements(path.read_text(encoding='utf-8')):
            if 'KTO_KOR_SERVICE_2' not in statement or 'current_revision' not in statement:
                continue
            if 'UPDATE source_registry' in statement and "WHERE code = 'KTO_KOR_SERVICE_2'" in statement:
                found = _REVISION.findall(statement)
                if len(found) != 1:
                    raise SystemExit('prepare-release: unreadable catalog revision change in ' + path.name)
                revisions.append(int(found[0]))
            elif 'INSERT INTO source_registry' in statement and 'UPDATE' not in statement:
                continue  # V007 seeds the row; later UPDATEs move it.
            elif 'source_registry_revisions' in statement:
                continue  # revision history rows, not the current pointer
            else:
                raise SystemExit('prepare-release: unrecognised statement moving the catalog revision in ' + path.name)
    if not revisions:
        raise SystemExit('prepare-release: no catalog revision found in migrations')
    return 'KTO_KOR_SERVICE_2:' + str(revisions[-1])

def overlay_paths():
    return list(_OVERLAY_PATHS)

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--print-image-tag',action='store_true',help='print the ECR tag this tree must be published under')
    p.add_argument('--web-dir');p.add_argument('--api-image-digest')
    p.add_argument('--ai-image-digest');p.add_argument('--release-version')
    p.add_argument('--build-run-id');p.add_argument('--ai-catalog-version')
    p.add_argument('--out')
    a=p.parse_args()
    git_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    state, overlay = source_state()
    if a.print_image_tag:
        print(image_tag({'gitSha': git_sha, 'sourceState': state, 'sourceOverlaySha256': overlay}))
        raise SystemExit(0)
    migrations = sorted((ROOT/'apps/api/src/main/resources/db/migration').glob('V*.sql'), key=lambda f: int(f.name[1:].split('__')[0]))
    derived = catalog_version(migrations)
    if a.ai_catalog_version and a.ai_catalog_version != derived:
        p.error('--ai-catalog-version ' + a.ai_catalog_version + ' disagrees with the migrations (' + derived + ')')
    a.ai_catalog_version = derived
    for name in ['web_dir','api_image_digest','ai_image_digest','release_version','build_run_id','out']:
        if not getattr(a, name):
            p.error('--' + name.replace('_', '-') + ' is required')
    m={'releaseVersion':a.release_version,'gitSha':git_sha,
       'apiImageDigest':a.api_image_digest,'aiImageDigest':a.ai_image_digest,'aiCatalogVersion':a.ai_catalog_version,
       'webArtifactSha256':'sha256:'+tree_digest(a.web_dir),
       'openApiSha256':'sha256:'+digest(ROOT/'docs/api/openapi.yaml'),
       'eventSchemaSha256':'sha256:'+digest(ROOT/'docs/contracts/events.schema.json'),
       'flywayChecksums':[f.name+':'+digest(f) for f in sorted((ROOT/'apps/api/src/main/resources/db/migration').glob('V*.sql'))],
       'buildRunId':a.build_run_id,'approvedByRoles':['BE_AI_DRI','FE_DRI'],'sourceState':state}
    if overlay:
        m['sourceOverlaySha256']=overlay
        m['sourceOverlayPaths']=overlay_paths()
    # Role labels are ownership metadata. Actual local authorization is the reviewed plan hash + execute.
    write_private(Path(a.out),m)
    validate_manifest(a.out)
    print('release_manifest=prepared approval_granted=false source_state='+state)
