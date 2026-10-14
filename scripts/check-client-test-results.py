#!/usr/bin/env python3
"""Require executed, unskipped regression tests; absent XML is not a pass."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path)
parser.add_argument('--minimum', type=int, required=True)
args = parser.parse_args()
files = list(args.directory.rglob('TEST-*.xml'))
if not files:
    raise SystemExit(f'No JUnit results in {args.directory}')
counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
for file in files:
    for suite in ET.parse(file).getroot().iter('testsuite'):
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
print(counts)
if counts['tests'] < args.minimum or any(counts[key] for key in ('failures', 'errors', 'skipped')):
    raise SystemExit('Client regression test gate failed')
