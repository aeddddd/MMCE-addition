#!/usr/bin/env python3
"""Verify the packaged addon against locked real dependencies on exact Cleanroom 0.4.0."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('side',choices=['client','server'])
p.add_argument('--dependencies',type=Path,required=True)
p.add_argument('--name',default='ae2s-acceptance')
p.add_argument('--port',type=int,default=25881)
p.add_argument('--timeout',type=int,default=300)
a=p.parse_args()
if not a.name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in a.name):
    p.error('Invalid isolated scenario name')
run=ROOT/'build/verification'/a.name/a.side
for source,relative in [('machinery','config/modularmachinery/machinery'),('scripts','scripts')]:
    target=run/relative;target.mkdir(parents=True,exist_ok=True)
    for fixture in (ROOT/'verification'/source).iterdir():
        if fixture.is_file():shutil.copyfile(fixture,target/fixture.name)
mods=[]
for entry in json.loads((ROOT/'verification/dependencies.json').read_text()):
    if entry.get('server_only') and a.side!='server':continue
    jar=a.dependencies/entry['file']
    with jar.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
    if actual!=entry['sha256']:raise SystemExit('Dependency hash mismatch: '+str(jar))
    mods.append(jar)
mods += [ROOT/'build/libs/MMCE-addition-1.4.4-ae2s.2.jar',ROOT/'build/verification/mmce-addition-verification-srg.jar']
command=[sys.executable,str(ROOT/'verification/launch.py'),a.side,'--name',a.name,'--port',str(a.port),
         '--timeout',str(a.timeout),'--report','verification-report.json']
for jar in mods:command+=['--mod',str(jar)]
raise SystemExit(subprocess.call(command))
