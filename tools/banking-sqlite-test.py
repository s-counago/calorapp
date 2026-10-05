"""Run the app's actual CREATE statements with SQLite, using fictional ciphertext only."""
import pathlib
import re
import sqlite3
import tempfile

source = (pathlib.Path(__file__).parents[1] / 'app/src/main/java/com/sejio/calorapp/BankingDatabase.java').read_text()
schema = re.findall(r'^\s*"(CREATE (?:TABLE|INDEX) [^"]+)"', source, re.M)
assert len(schema) == 9
with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory) / 'test.sqlite'
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    for statement in schema:
        db.execute(statement)
    db.execute("INSERT INTO sync_runs(id,bank,started_at,status,source) VALUES('r','abanca',1,'collecting','test')")
    db.execute("INSERT INTO products VALUES('p','abanca','card',1,?)", (b'fictional-encrypted-product',))
    db.execute("INSERT INTO captures VALUES('c','r','p',1,'test','test',?)", (b'fictional-encrypted-capture',))
    db.execute("INSERT INTO entities(id,product_id,type,observed_at,latest_capture,payload) VALUES('e','p','movement',1,'c',?)", (b'fictional-encrypted-movement',))
    for row in (1, 2):
        db.execute("INSERT INTO observations VALUES('c',?,'e',0,?,?)", (row, row, b'fictional-encrypted-observation'))
    db.commit()
    assert db.execute('SELECT COUNT(*) FROM entities').fetchone()[0] == 1
    assert db.execute('SELECT COUNT(*) FROM observations').fetchone()[0] == 2
    try:
        with db:
            db.execute("INSERT INTO captures VALUES('bad','r','p',2,'test','test',?)", (b'test',))
            db.execute("INSERT INTO observations VALUES('bad',0,'missing-entity',0,0,?)", (b'test',))
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError('foreign key failure expected')
    assert db.execute("SELECT COUNT(*) FROM captures WHERE id='bad'").fetchone()[0] == 0
    db.close()
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    assert db.execute('SELECT COUNT(*) FROM observations').fetchone()[0] == 2
    with db:
        db.execute("UPDATE sync_runs SET status='interrupted' WHERE status='collecting'")
    assert db.execute('SELECT status FROM sync_runs').fetchone()[0] == 'interrupted'
    with db:
        db.execute("DELETE FROM sync_runs WHERE bank='abanca'")
        db.execute("DELETE FROM products WHERE bank='abanca'")
    for table in ('sync_runs', 'products', 'captures', 'entities', 'observations'):
        assert db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 0
    db.close()
print('PASS: SQLite schema, durable reopen, row provenance, rollback, foreign keys and explicit deletion')
