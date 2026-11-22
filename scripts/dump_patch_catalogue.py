"""Dump real catalogue rows and audit the same conservative categories used by the app."""
import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
URL = 'https://morphe-patches.software/data/bundles.json'
RULES = ROOT / 'app/src/main/resources/universal-categories.json'


def category(name, description, declared, rules):
    normalized = re.sub(r'^[^\w]+', '', (declared or '').strip()).lower()
    for rule in rules['categories']:
        if normalized in [rule['name'].lower(), *rule['aliases']]:
            return rule['name'], 'declared'
    # Name is more specific than long descriptions mentioning other patches or warnings.
    for value in [name, description or '']:
        for rule in rules['categories']:
            if re.search(rule['pattern'], value, re.I):
                return rule['name'], 'derived'
    if normalized and normalized not in ['misc', 'general', 'featured', 'disable', 'enable',
                                         'force', 'hide', 'ignore', 'allow', 'bypass', 'fake', 'spoof', 'prompts']:
        return declared.strip(), 'declared'
    return rules['fallback'], 'other'


def compatibility(patch, shared):
    for key in ['compatibilityKey', 'compatiblePackagesKey']:
        if key in patch:
            index = patch[key]
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(shared):
                raise ValueError('Invalid shared compatibility reference')
            return shared[index]
    return patch.get('compatibility', patch.get('compatiblePackages'))


def dump(data, rules):
    rows, errors = [], []
    for bundle in data['bundles']:
        for index, patch in enumerate(bundle.get('patches', [])):
            try:
                targets = compatibility(patch, data.get('compatibilities', []))
                universal = not targets or isinstance(targets, list) and any(t.get('packageName') is None for t in targets)
                group, origin = category(patch['name'], patch.get('description'), patch.get('category'), rules)
                rows.append(dict(source=bundle['source'], repo=bundle['repo'], index=index,
                                 name=patch['name'], description=patch.get('description'),
                                 declaredCategory=patch.get('category'), compatibility=targets,
                                 universal=universal, category=group if universal else None,
                                 categoryOrigin=origin if universal else None))
            except (ValueError, TypeError, IndexError, KeyError) as error:
                errors.append(dict(repo=bundle.get('repo'), index=index, error=str(error)))
    universal = [r for r in rows if r['universal']]
    return dict(rows=rows, errors=errors, summary=dict(bundles=len(data['bundles']), patches=len(rows),
                universal=len(universal), categories=dict(collections.Counter(r['category'] for r in universal)),
                origins=dict(collections.Counter(r['categoryOrigin'] for r in universal)),
                unmatched=[dict(repo=r['repo'], name=r['name']) for r in universal if r['categoryOrigin']=='other']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalogue', type=pathlib.Path, help='Use an existing raw catalogue, without network')
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args()
    raw = args.catalogue.read_bytes() if args.catalogue else subprocess.check_output(
        ['curl.exe', '-fLsS', '-A', 'Mozilla/5.0', URL])
    result = dump(json.loads(raw), json.loads(RULES.read_text(encoding='utf-8')))
    result['provenance'] = dict(url=URL, capturedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                catalogueSha256=hashlib.sha256(raw).hexdigest(), rulesSha256=hashlib.sha256(RULES.read_bytes()).hexdigest())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(summary=result['summary'], errors=result['errors'], output=str(args.output)), ensure_ascii=True))
    if result['errors']:
        raise SystemExit(1)
