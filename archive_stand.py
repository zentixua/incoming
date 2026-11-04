from pathlib import Path
import gzip,shutil,hashlib,json
source=Path('/mnt/data/projects/incoming/mod/run/stand/out/mt-b5-final-dba2')
target=Path(__file__).resolve().parent/'evidence'/'scene-bench';target.mkdir(exist_ok=True)
manifest={}
for p in sorted(source.rglob('*')):
 if not p.is_file():continue
 rel=p.relative_to(source)
 if 'shots' in rel.parts:continue
 # Root report contact sheets are retained; individual conventional scene shots remain local.
 dst=target/rel;dst.parent.mkdir(parents=True,exist_ok=True)
 if p.suffix=='.csv':
  dst=dst.with_suffix(dst.suffix+'.gz')
  with p.open('rb') as src,dst.open('wb') as raw:
   with gzip.GzipFile(filename='',mode='wb',fileobj=raw,compresslevel=1,mtime=0) as out:
    shutil.copyfileobj(src,out)
 else:shutil.copy2(p,dst)
 manifest[str(rel)]={'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,'archived':str(dst.relative_to(target))}
(target/'archive-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(target/'ARCHIVE.md').write_text('Raw frame CSVs are losslessly gzip-compressed. Uncompress into a separate copy before re-running the stand parser. The manifest records original raw hashes and paths. Individual conventional scene screenshots remain in the local stand directory; generated report contact sheets are retained here. Paired nuclear coverage frames are separately under ../frames. Original runner jar metadata snapshots the initial candidate: see ../bench-jar-identity.json for the observed per-pack correction.\n')
print('archived',len(manifest),'files')
