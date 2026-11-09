"""Generate the installed candidate identity without changing product versions."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ASSET = 'assets/aerove/candidate_identity.json'
FIELDS = {'schema', 'product', 'source', 'tree', 'runId', 'runNumber', 'attempt', 'abi'}


def validate(value):
    if not isinstance(value, dict) or set(value) != FIELDS or type(value['schema']) is not int or value['schema'] != 1 or value['product'] != 'aerove-lite':
        raise ValueError('Invalid candidate identity schema')
    if any(not isinstance(value[key], str) or not re.fullmatch(r'[0-9a-f]{40}', value[key]) for key in ('source', 'tree')):
        raise ValueError('Invalid candidate source identity')
    if any(not isinstance(value[key], str) or not re.fullmatch(r'[1-9][0-9]{0,19}', value[key]) for key in ('runId', 'runNumber', 'attempt')):
        raise ValueError('Invalid candidate workflow identity')
    if value['abi'] not in ('arm64-v8a', 'x86_64'):
        raise ValueError('Invalid candidate ABI')
    return value


def generate(root, abi, source, env):
    if env.get('GITHUB_ACTIONS') != 'true':
        return {'schema': 1, 'product': 'aerove-lite', 'identified': False}
    if env.get('GITHUB_REPOSITORY') != 'IvanSolis1989/Aerove':
        raise ValueError('Unexpected candidate repository')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    if source != head:
        raise ValueError('Candidate source does not match checkout')
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=root, text=True).strip()
    return validate(dict(schema=1, product='aerove-lite', source=head, tree=tree,
        runId=env.get('GITHUB_RUN_ID'), runNumber=env.get('GITHUB_RUN_NUMBER'),
        attempt=env.get('GITHUB_RUN_ATTEMPT'), abi=abi))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--abi', required=True)
    parser.add_argument('--source', default='')
    args = parser.parse_args()
    value = generate(Path(__file__).resolve().parents[3], args.abi, args.source, os.environ)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(value, sort_keys=True) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
