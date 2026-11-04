#!/usr/bin/env python3
"""Offline bounded streaming audit, pinned disk schema; never opens a world for writing."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct
import zlib

SOURCE = '4d89b5b113a53b3cfe8822092e1813750516c9e9'
MASK = (1 << 256) - 1
MAX_ROW = 8 << 20
MAX_IDENTITIES = 4096

class Unsupported(Exception): pass
class Invalid(Exception): pass

def canonical(x):
    return json.dumps(x, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode()
def digest(x): return hashlib.sha256(canonical(x)).hexdigest()
class Fold:
    def __init__(self): self.count = 0; self.total = 0; self.order = hashlib.sha256()
    def add(self, x):
        h = bytes.fromhex(digest(x)); self.count += 1
        self.total = (self.total + int.from_bytes(h, 'big')) & MASK; self.order.update(h)
    def result(self):
        return {'count': self.count, 'sum_sha256_mod_2_256': f'{self.total:064x}', 'ordered_sha256': self.order.hexdigest()}

def require(row, keys):
    missing = set(keys) - row.keys()
    if missing: raise Unsupported('Missing required fields: ' + ','.join(sorted(missing)))
def nonnegative(*xs):
    if any(not isinstance(x, (int, float)) or not math.isfinite(x) or x < 0 for x in xs):
        raise Invalid('Negative/nonfinite physical quantity')
def identity(state):
    if not isinstance(state, dict) or not isinstance(state.get('Name'), str):
        raise Unsupported('Block state has no Name; registry resolution is intentionally unavailable')
    if set(state)-{'Name','Properties'}:raise Unsupported('Unknown block state fields')
    if 'Properties' in state and (not isinstance(state['Properties'],dict) or not all(isinstance(v,str) for v in state['Properties'].values())):raise Unsupported('Block state properties must be strings')
    return state['Name']

class Reader:
    def __init__(self, stream, audit): self.f = stream; self.a = audit; self.pos = 0; self.row_start = None
    def read(self, n):
        if n < 0: raise Invalid('Negative length')
        if self.row_start is not None and self.pos + n - self.row_start > MAX_ROW:
            raise Unsupported('Ordinary row exceeds 8 MiB bound')
        b = self.f.read(n); self.pos += len(b)
        if len(b) != n: raise Invalid('Truncated NBT')
        return b
    def num(self, fmt): return struct.unpack('>' + fmt, self.read(struct.calcsize('>' + fmt)))[0]
    def string(self):
        # Java modified UTF-8: NUL and UTF-16 surrogate code units differ from UTF-8.
        b = self.read(self.num('H')).replace(b'\xc0\x80', b'\0')
        try:
            s = b.decode('utf-8', 'surrogatepass')
            return s.encode('utf-16-be', 'surrogatepass').decode('utf-16-be')
        except UnicodeError as e: raise Invalid('Invalid modified UTF string') from e
    def payload(self, t, path=(), depth=0):
        if depth > 64: raise Unsupported('NBT nesting exceeds 64')
        self.a.check_type(path, t)
        if t in range(1,7):
            v = self.num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
            if isinstance(v,float) and not math.isfinite(v): raise Invalid('Nonfinite NBT number')
            return {'nbt_scalar_type':t,'value':v} if 'contents' in path else v
        if t == 8:
            v=self.string()
            return {'nbt_scalar_type':8,'value':v} if 'contents' in path else v
        if t in (7,11,12):
            n = self.num('i')
            if n < 0: raise Invalid('Negative array length')
            if path[-1:] == ('motion',):
                if t != 7 or n != 145: raise Unsupported('Motion must be a 145-byte disk record')
                return {'motion_hex': self.read(n).hex()}
            fold = Fold(); fmt = {7:'b',11:'i',12:'q'}[t]
            for _ in range(n):
                start = self.pos
                fold.add(self.num(fmt))
                if self.row_start is not None: self.row_start += self.pos-start
            return {'nbt_array_type':t, **fold.result()}
        if t == 9:
            et, n = self.num('B'), self.num('i')
            if n < 0 or et not in range(13) or (et == 0 and n): raise Invalid('Invalid list header')
            fold = Fold()
            schema_list = self.a.is_rows(path)
            if schema_list and et != 10 and not (n == 0 and et == 0): raise Unsupported('Ledger rows must be compounds')
            for _ in range(n):
                old = self.row_start; start = self.pos
                self.row_start = start
                row = self.payload(et, path + ('[]',), depth+1)
                self.row_start = None if old is None else old + self.pos-start
                if schema_list: self.a.row(path, row)
                fold.add(row)
            return {'nbt_list_type':et, **fold.result()}
        if t == 10:
            row = {}
            while True:
                et = self.num('B')
                if et == 0: return {'nbt_compound':row} if 'contents' in path else row
                name = self.string()
                if name in row: raise Invalid('Duplicate compound field ' + name)
                if len(row) >= 256: raise Unsupported('Compound exceeds 256 fields')
                # Progress event rows must also be streamed rather than retained at data root.
                if path == ('data',) and self.a.kind == 'progress':
                    if et != 10 or not name.lstrip('-').isdigit(): raise Unsupported('Unknown progress event field')
                    old = self.row_start; self.row_start = self.pos
                    value = self.payload(et, path+(name,), depth+1); self.row_start = old
                    self.a.progress(name, value)
                    continue
                row[name] = self.payload(et, path+(name,), depth+1)
        raise Unsupported('Unknown NBT type ' + str(t))

class Audit:
    def __init__(self, kind):
        self.kind = kind; self.rows = {}; self.pending = [0,0.,0.]; self.cargo = [0,0,0]
        self.blocks = {}; self.materials = [0.]*6; self.crater = [0.]*8
        self.progress_fold = Fold(); self.progress_count = 0; self.progress_samples = []; self.progress_pending = 0; self.archives = Fold(); self.complete_count=0
        self.crater_samples=[]; self.crater_passes=0
        self.failures = []; self.motion_formats = {}; self.available = 0; self.quantities = {}; self.semantic_moving = Fold()
    def schema(self, p):
        if p == (): return {'DataVersion':3,'data':10}
        if self.kind == 'ruin':
            if p == ('data',):
                return {**{k:9 for k in ('materials','moving','stranded','support_sources','support_originals','cold_edges')},'supports':12,'support_events':11,
                        **{k:4 for k in ('released','deposited','dispersed')},
                        **{k+s:6 for k in ('released','deposited','dispersed') for s in ('_volume','_mass')}}
            if p == ('data','materials','[]'): return {'block':8,**{'v'+str(i):6 for i in range(6)}}
            if p in (('data','support_sources','[]'),('data','support_originals','[]')):return {'position':4,'event':3,'state':10}
            if p == ('data','cold_edges','[]'):return {'position':4,**{'face_'+str(i):10 for i in range(6)}}
            if p == ('data','stranded','[]'):return {'state':10,'contents':10,'count':4,'volume':6,'mass':6}
            if p == ('data','moving','[]'):
                return {'motion':7,'state':10,'contents':10,'source':4,'id':4,'scorch':3,'scorch_event':3,'scorch_time':4,'outside':1,
                        **{k:6 for k in ('volume','mass','x','y','z','vx','vy','vz','tick','released_tick','wind_x','wind_z','drag','pulse')}}
        if self.kind == 'crater':
            if p == ('data',):return {'passes':9,'settled':9}
            if p == ('data','passes','[]'):return {'event':3,'soil':8,'columns':12,'ground':11,'done':12,'materials':9}
            if p in (('data','settled','[]'),('data','passes','[]','materials','[]')):
                return {'state':10,'available':4,**{'v'+str(i):6 for i in range(8)}}
        if self.kind == 'nuclear' and p==('data',):return {'next_id':3,'late_from':3,'detonations':9,'past':9,'scheduled':9,'craters':10}
        if self.kind == 'progress' and len(p)==2 and p[0]=='data':
            return {'cursor':4,'complete':1,'archive':8,'scan_version':3,'pending':12}
        return None
    def check_type(self, path, tag):
        if not path:return
        if self.kind=='nuclear' and path[:-1]==('data','craters'):
            if not path[-1].lstrip('-').isdigit() or tag!=3:raise Unsupported('Unknown crater cursor schema')
        schema = self.schema(path[:-1])
        if schema is not None:
            if path[-1] not in schema:raise Unsupported('Unknown field '+ '/'.join(path))
            if schema[path[-1]] != tag:raise Unsupported('Unexpected NBT type at '+ '/'.join(path))
    def is_rows(self, p):
        if self.kind == 'ruin': return len(p)==2 and p[0]=='data' and p[1] in ('moving','stranded','materials','support_sources','support_originals','cold_edges')
        if self.kind == 'crater': return p in (('data','passes'),('data','settled'),('data','passes','[]','materials'))
        return False
    def block(self, key):
        if key not in self.blocks:
            if len(self.blocks) >= MAX_IDENTITIES: raise Unsupported('More than 4096 block identities')
            self.blocks[key] = {'pending':[0.,0.], 'ledger':[0.]*6, 'rows':0}
        return self.blocks[key]
    def row(self, path, row):
        key = '/'.join(path); self.rows.setdefault(key,Fold()).add(row)
        name = path[-1]
        if self.kind == 'ruin':
            if name == 'materials':
                require(row,['block']+['v'+str(i) for i in range(6)])
                vals = [row['v'+str(i)] for i in range(6)]; nonnegative(*vals)
                b = self.block(row['block']); b['rows'] += 1
                if b['rows'] > 1: raise Unsupported('Duplicate material block row (ambiguous overwrite)')
                b['ledger'] = vals
                self.materials = [a+b for a,b in zip(self.materials,vals)]
            elif name in ('moving','stranded'):
                require(row,['state']); block = identity(row['state'])
                if name == 'moving':
                    if 'motion' in row:
                        values = struct.unpack('>qqiiq14dB', bytes.fromhex(row['motion']['motion_hex']))
                        vals = values[5:19]; nonnegative(vals[0],vals[1])
                        if not all(math.isfinite(x) for x in vals): raise Invalid('Nonfinite packed motion')
                        names=('source','id','scorch','scorch_event','scorch_time','volume','mass','x','y','z','vx','vy','vz','tick','released_tick','wind_x','wind_z','drag','pulse','outside')
                        semantic=dict(zip(names,values))
                        if values[-1] not in (0,1): raise Invalid('Invalid outside flag')
                        volume,mass = vals[:2]; fmt='packed145'
                    else:
                        require(row,['source','id','scorch','scorch_event','scorch_time','volume','mass','x','y','z','vx','vy','vz','tick','released_tick','wind_x','wind_z','drag','pulse','outside'])
                        volume,mass=row['volume'],row['mass']; fmt='legacy_named'
                        semantic={k:v for k,v in row.items() if k not in ('state','contents')}
                        for k in ('volume','mass','x','y','z','vx','vy','vz','tick','released_tick','wind_x','wind_z','drag','pulse'):semantic[k]=float(semantic[k])
                    semantic['state']=row['state']
                    if 'contents' in row:semantic['contents']=row['contents']
                    self.semantic_moving.add(semantic)
                    self.motion_formats[fmt] = self.motion_formats.get(fmt,0)+1; count=1
                else:
                    require(row,['count','volume','mass']); count,volume,mass=row['count'],row['volume'],row['mass']
                    if count <= 0: raise Invalid('Stranded count must be positive')
                nonnegative(volume,mass)
                q=self.quantities.setdefault(name,[0,0.,0.]); q[0]+=count; q[1]+=volume; q[2]+=mass
                self.pending = [self.pending[0]+count,self.pending[1]+volume,self.pending[2]+mass]
                b=self.block(block); b['pending']=[b['pending'][0]+volume,b['pending'][1]+mass]
                if 'contents' in row:
                    self.cargo[0]+=1; self.cargo[1]+=count; self.cargo[2]+=len(canonical(row['contents']))
            elif name in ('support_sources','support_originals'):
                require(row,['position','event','state']); identity(row['state'])
            elif name == 'cold_edges':
                require(row,['position'])
                for k,v in row.items():
                    if k.startswith('face_'): identity(v)
        elif self.kind == 'crater':
            if name in ('materials','settled'):
                require(row,['state']+['v'+str(i) for i in range(8)]); identity(row['state'])
                vals=[row['v'+str(i)] for i in range(8)]; nonnegative(*vals)
                self.crater=[a+b for a,b in zip(self.crater,vals)]
                for i in (0,1): self.balance(vals[i],sum(vals[i+2::2]),'crater row '+str(i))
                if name == 'materials':
                    require(row,['available']); nonnegative(row['available']); self.available+=row['available']
            elif name == 'passes':
                require(row,['event','soil','columns','ground','done','materials'])
                if row['columns']['count'] != row['ground']['count']: raise Invalid('Crater checkpoint array length mismatch')
                self.crater_passes+=1
                if len(self.crater_samples)<16:self.crater_samples.append(row)
    def progress(self, event, row):
        require(row,['cursor','complete','pending'])
        version=row.get('scan_version',0)
        if version not in (0,1,2): raise Unsupported('Unknown scan_version '+str(version))
        self.progress_count+=1; self.progress_fold.add({'event':event,**row})
        self.progress_pending+=row['pending']['count']; self.complete_count+=bool(row['complete'])
        if row.get('archive'):self.archives.add({'event':event,'archive':row['archive']})
        sample={'event':event,**row,'effective_cursor':(2**63-1 if row['complete'] else max(0,row['cursor']) if version==2 else 0)}
        if len(self.progress_samples)<16:self.progress_samples.append(sample)
    def balance(self, released, accounted, label):
        residual=released-accounted
        if not math.isfinite(residual) or abs(residual)>1e-9*max(1,abs(accounted)):
            if len(self.failures)<32:self.failures.append({'check':label,'residual':residual})
        return residual
    def finish(self, root):
        require(root,['DataVersion','data']); data=root['data']
        out={'row_digests':{k:v.result() for k,v in self.rows.items()}}
        if self.kind=='ruin':
            fields=['released','deposited','dispersed','released_volume','deposited_volume','dispersed_volume','released_mass','deposited_mass','dispersed_mass']
            require(data, fields+['materials','moving','supports','support_events','support_sources','support_originals','cold_edges'])
            unknown=set(data)-set(fields+['materials','moving','supports','support_events','support_sources','support_originals','cold_edges','stranded'])
            if unknown:raise Unsupported('Unknown ruin fields: '+str(sorted(unknown)))
            nonnegative(*(data[k] for k in fields))
            if data['supports']['count']!=data['support_events']['count']:raise Invalid('Support array length mismatch')
            residuals={}
            for i,suffix in enumerate(('', '_volume','_mass')):
                r=data['released'+suffix]; acc=data['deposited'+suffix]+data['dispersed'+suffix]+self.pending[i]
                residuals[suffix or 'count']=self.balance(r,acc,'global'+suffix)
                if i==0 and r!=acc:self.failures.append({'check':'exact count','residual':r-acc})
            for key,b in self.blocks.items():
                if b['rows']==0:raise Unsupported('Pending block lacks material totals: '+key)
                for i in (0,1):self.balance(b['ledger'][i],b['ledger'][i+2]+b['ledger'][i+4]+b['pending'][i],key+str(i))
            out.update(totals={k:data[k] for k in fields}, pending_count_volume_mass=self.pending,
                       cargo={'rows':self.cargo[0],'represented_pieces':self.cargo[1],'canonical_summary_bytes':self.cargo[2], 'note':'Cargo contents hashed structurally; no registry/item-stack guesses'},
                       moving_and_stranded_count_volume_mass=self.quantities, semantic_moving_digest=self.semantic_moving.result(), motion_formats=self.motion_formats, material_v0_to_v5=self.materials,residuals=residuals,
                       support_arrays={k:data[k] for k in ('supports','support_events')})
        elif self.kind=='crater':
            require(data,['passes','settled'])
            if set(data)-{'passes','settled'}:raise Unsupported('Unknown crater root fields')
            out.update(pass_count=self.crater_passes,pass_samples=self.crater_samples,samples_truncated=self.crater_passes>16,material_v0_to_v7=self.crater,available_count=self.available, residuals={str(i):self.balance(self.crater[i],sum(self.crater[i+2::2]),'crater total '+str(i)) for i in (0,1)})
        elif self.kind=='nuclear':
            require(data,['craters','next_id','late_from','detonations','past','scheduled'])
            for cursor in data['craters'].values():nonnegative(cursor)
            out.update(crater_chunk_cursors=data['craters'],next_id=data['next_id'],late_from=data['late_from'],event_lists={k:data[k] for k in ('detonations','past','scheduled')})
        else:
            out.update(pending_chunk_count=self.progress_pending,complete_events=self.complete_count,archives_digest=self.archives.result(),events=self.progress_count,events_digest=self.progress_fold.result(),event_samples=self.progress_samples,samples_truncated=self.progress_count>16)
        out['data_version']=root['DataVersion']; out['failures']=self.failures
        return out

def audit_file(path):
    p=Path(path); kinds={'incoming_ruin_matter.dat':'ruin','incoming_crater_matter.dat':'crater','incoming_ruin_settle.dat':'progress','incoming_nuclear.dat':'nuclear'}
    report={'audit_schema_version':1,'source_sha':SOURCE,'path':str(p),'status':'unsupported'}
    try:
        if p.name not in kinds:raise Unsupported('Unrecognized ledger filename')
        before=p.stat(); sha=hashlib.sha256()
        with p.open('rb') as f:
            for chunk in iter(lambda:f.read(65536),b''):sha.update(chunk)
        report['file_sha256']=sha.hexdigest(); report['file_bytes']=before.st_size
        a=Audit(kinds[p.name])
        with gzip.open(p,'rb') as f:
            r=Reader(f,a)
            if r.num('B')!=10 or r.string()!='':raise Unsupported('Expected unnamed root compound')
            root=r.payload(10)
            if f.read(1):raise Invalid('Trailing decompressed bytes')
        after=p.stat()
        if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise Invalid('File changed during read')
        report.update(kind=a.kind,**a.finish(root)); report['status']='fail' if a.failures else 'pass'
    except Unsupported as e:report.update(status='unsupported',error=str(e))
    except (Invalid,OSError,EOFError,ValueError,KeyError,TypeError,struct.error,zlib.error) as e:report.update(status='fail',error=str(e))
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('files',nargs='+'); args=parser.parse_args()
    reports=[audit_file(p) for p in args.files]
    print(json.dumps({'audit_schema_version':1,'source_sha':SOURCE,'files':reports},indent=2,allow_nan=False))
    return 1 if any(r['status']=='fail' for r in reports) else 2 if any(r['status']=='unsupported' for r in reports) else 0
if __name__=='__main__':raise SystemExit(main())
