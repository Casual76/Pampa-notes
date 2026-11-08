"""Independent in-process reproduction of the stale pre-start error disposition.
Uses the exact current production @Query SQL and schema. No Room or WorkManager runtime.
Run from the reviewed checkout: python -B <this file>
"""
import hashlib,json,pathlib,re,sqlite3
ROOT=pathlib.Path.cwd()
SOURCE=(ROOT/'core/src/main/kotlin/dev/pampa/pampanotes/core/db/Daos.kt').read_text(encoding='utf-8')
SCHEMA=json.loads((ROOT/'core/schemas/dev.pampa.pampanotes.core.db.PampaDatabase/15.json').read_text())['database']
JOBS=next(e for e in SCHEMA['entities'] if e['tableName']=='jobs')
def query(name):
 stop=SOURCE.index('suspend fun '+name+'(');start=SOURCE.rfind('@Query(',0,stop)
 return ''.join(json.loads('"'+v+'"') for v in re.findall(r'"((?:\\.|[^"\\])*)"',SOURCE[start:stop]))
db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
db.execute(JOBS['createSql'].replace('${TABLE_NAME}','jobs'))
row={f['columnName']:(None if not f.get('notNull',False) else 0 if f['affinity'] in ('INTEGER','REAL') else '') for f in JOBS['fields']}
row.update(id='job',sessionId='session',type='TRANSCRIBE',provider='openrouter',state='QUEUED',model='folder/A',optionsJson='{"cloudChunkMinutes":10,"groqMaxUploadMb":25}',createdAt=1,updatedAt=1)
db.execute('INSERT INTO jobs ('+','.join('`'+x+'`' for x in row)+') VALUES ('+','.join('?' for _ in row)+')',list(row.values()))
p=dict(id='job',now=2,options=row['optionsJson'],**{'from':'openrouter','to':'custom'})
assert db.execute(query('moveTranscription'),p).rowcount==1
print('AFTER PRIVACY MIGRATION',dict(db.execute("SELECT provider,state,model FROM jobs WHERE id='job'").fetchone()))
assert db.execute(query('nextQueued'),{'provider':'custom'}).fetchone() is not None
# This is the outer doWork generic error disposition for CompanionOnly escaping transcribe:
# TranscriptionQueueWorker.kt:164-174 -> fail at 718 -> repository.fail -> this exact SQL.
p.update(code='companion_only',message='Cloud transcription disabled',now=3)
assert db.execute(query('fail'),p).rowcount==1
print('AFTER STALE WORKER FAIL',dict(db.execute("SELECT provider,state,model,errorCode FROM jobs WHERE id='job'").fetchone()))
assert db.execute(query('nextQueued'),{'provider':'custom'}).fetchone() is None
assert db.execute(query('requeueInterrupted'),{'updatedAt':4}).rowcount==0
print('CUSTOM QUEUE HAS NO JOB; process restart does not recover FAILED row')
print('CONFIRMED: companion delivery now needs explicit manual retry; no cloud upload occurred')
