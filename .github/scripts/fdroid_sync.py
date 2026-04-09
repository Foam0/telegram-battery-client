#!/usr/bin/env python3
"""Sync a Mercurygram release into a local fdroiddata checkout.

Steps per release tag:
  1. Verify each ABI APK with fdroidserver and extract its v1+v2/v3 signature
     block into metadata/<appid>/signatures/<vc>/.
  2. Edit metadata/<appid>.yml via ruamel.yaml (round-trip), appending one
     Builds: entry per ABI (cloned from the most recent same-flavor entry,
     with versionName/versionCode/commit replaced).
  3. Bump CurrentVersion / CurrentVersionCode.

Usage: fdroid_sync.py <tag> <apks_dir> <fdroiddata_root> <mercurygram_repo>
"""

import copy
import glob
import os
import re
import subprocess
import sys

from fdroidserver import common
from ruamel.yaml import YAML

APPID = 'it.belloworld.mercurygram'
FLAVOR_OFFSETS = {
    'afatFdX86':    3,
    'afatFdX86_64': 4,
    'afatFdArm32':  7,
    'afatFdArm64':  8,
}


def main(tag: str, apks_dir: str, fdroiddata_root: str, mg_repo: str) -> None:
    with open(os.path.join(mg_repo, 'gradle.properties')) as fh:
        gp = fh.read()
    vc_base = int(re.search(r'^MG_VERSION_CODE=(\d+)', gp, re.M).group(1))
    vn = re.search(r'^MG_VERSION_NAME=([\d.]+)', gp, re.M).group(1)
    with open(os.path.join(mg_repo, 'TMessagesProj', 'build.gradle')) as fh:
        ndk_ver = re.search(r'ndkVersion\s+"([\d.]+)"', fh.read()).group(1)
    sha = subprocess.check_output(
        ['git', '-C', mg_repo, 'rev-list', '-n', '1', tag]
    ).strip().decode()

    common.read_config()
    sigroot = os.path.join(fdroiddata_root, 'metadata', APPID, 'signatures')

    for apk in sorted(glob.glob(os.path.join(apks_dir, '*.apk'))):
        if not common.verify_apk_signature(apk):
            sys.exit(f'invalid sig: {apk}')
        _, vc, _ = common.get_apk_id(apk)
        d = os.path.join(sigroot, str(vc))
        os.makedirs(d, exist_ok=True)
        common.apk_extract_signatures(apk, d)
        print(f'extracted {apk} -> {d}', file=sys.stderr)

    yaml = YAML(typ='rt')
    yaml.width = 80  # match fdroiddata's default folding so diffs stay minimal
    yaml.indent(mapping=2, sequence=4, offset=2)
    ymlpath = os.path.join(fdroiddata_root, 'metadata', f'{APPID}.yml')
    with open(ymlpath) as fh:
        data = yaml.load(fh)

    builds = data['Builds']
    existing_vcs = {b.get('versionCode') for b in builds}

    for flavor, off in FLAVOR_OFFSETS.items():
        new_vc = vc_base * 10 + off
        if new_vc in existing_vcs:
            print(f'skip {flavor}: vc {new_vc} already present', file=sys.stderr)
            continue
        template = next(
            (b for b in reversed(builds) if b.get('gradle') == [flavor]),
            None,
        )
        if template is None:
            sys.exit(f'no template Builds entry for flavor {flavor}')
        new = copy.deepcopy(template)
        new.pop('disable', None)  # template may carry a disable from a broken release
        new['versionName'] = vn
        new['versionCode'] = new_vc
        new['commit'] = sha
        new['ndk'] = ndk_ver
        builds.append(new)
        print(f'appended {flavor} vc={new_vc}', file=sys.stderr)

    data['CurrentVersion'] = vn
    data['CurrentVersionCode'] = vc_base * 10 + max(FLAVOR_OFFSETS.values())

    with open(ymlpath, 'w') as fh:
        yaml.dump(data, fh)


if __name__ == '__main__':
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
