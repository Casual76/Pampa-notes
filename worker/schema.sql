-- L'indice: lo stato corrente di ogni riga sincronizzata, con un numero di sequenza per proprietario.
--
-- Non un registro di eventi: una riga per (proprietario, tabella, id), sovrascritta a ogni
-- modifica e con `seq` che sale. Cosi' il database e' grande quanto i dati, non quanto la loro
-- storia, e un pull da `since` legge solo quello che e' cambiato dopo.
--
-- I segmenti stanno a parte, a blocchi: una trascrizione di un'ora sono centinaia di righe con le
-- parole e i tempi, e una riga di D1 vale al massimo due megabyte. Un blocco sono al massimo
-- quattrocento segmenti, e una trascrizione e' una riga in `state` piu' pochi blocchi qui.

CREATE TABLE IF NOT EXISTS state (
  ownerId   TEXT    NOT NULL,
  tbl       TEXT    NOT NULL,
  rowId     TEXT    NOT NULL,
  op        TEXT    NOT NULL,             -- 'U' aggiornata, 'D' cancellata (tombstone)
  updatedAt INTEGER NOT NULL,             -- l'orologio del dispositivo che ha scritto
  hash      TEXT    NOT NULL DEFAULT '',  -- l'impronta del contenuto, per non ripubblicare l'uguale
  deviceId  TEXT    NOT NULL,
  receivedAt INTEGER NOT NULL DEFAULT 0,  -- l'orologio del server: e' lui a decidere quando un tombstone e' vecchio
  payload   TEXT,                         -- JSON della riga; NULL per un tombstone
  seq       INTEGER NOT NULL,
  PRIMARY KEY (ownerId, tbl, rowId)
);
CREATE INDEX IF NOT EXISTS state_seq ON state(ownerId, seq);
CREATE INDEX IF NOT EXISTS state_tombstones ON state(ownerId, op, receivedAt);

CREATE TABLE IF NOT EXISTS segment_chunks (
  ownerId      TEXT    NOT NULL,
  transcriptId TEXT    NOT NULL,
  chunk        INTEGER NOT NULL,
  payload      TEXT    NOT NULL,          -- JSON: un array di segmenti
  PRIMARY KEY (ownerId, transcriptId, chunk)
);

-- Il contatore di sequenza e il punto fino a cui i tombstone sono stati potati.
CREATE TABLE IF NOT EXISTS owners (
  ownerId   TEXT    PRIMARY KEY,
  seq       INTEGER NOT NULL DEFAULT 0,
  prunedSeq INTEGER NOT NULL DEFAULT 0    -- un pull con since < prunedSeq deve ricominciare da zero
);

CREATE TABLE IF NOT EXISTS devices (
  ownerId    TEXT NOT NULL,
  deviceId   TEXT NOT NULL,
  name       TEXT,
  lastSeenAt INTEGER NOT NULL,
  PRIMARY KEY (ownerId, deviceId)
);

-- I lotti di push gia' applicati: un lotto confermato ma perso per strada si rimanda uguale, e
-- qui si riconosce e si risponde come la prima volta, senza toccare niente.
CREATE TABLE IF NOT EXISTS batches (
  ownerId  TEXT    NOT NULL,
  batchId  TEXT    NOT NULL,
  seq      INTEGER NOT NULL,
  result   TEXT    NOT NULL,
  at       INTEGER NOT NULL,
  PRIMARY KEY (ownerId, batchId)
);

-- Le condivisioni: un link per nota. Il testo lo legge dall'indice al momento dell'apertura;
-- l'audio sta in R2 sotto <ownerId>/<shareId>/<partId>, e la revoca lo cancella per prefisso.
CREATE TABLE IF NOT EXISTS shares (
  ownerId    TEXT    NOT NULL,
  shareId    TEXT    NOT NULL,
  token      TEXT    NOT NULL,             -- la chiave del link: 24 byte casuali, non indovinabile
  noteId     TEXT    NOT NULL,
  title      TEXT    NOT NULL,             -- com'era la nota quando e' stata condivisa, per il pannello
  createdAt  INTEGER NOT NULL,
  revokedAt  INTEGER,                      -- NULL: viva
  openedAt   INTEGER,                      -- l'ultima apertura del link
  opens      INTEGER NOT NULL DEFAULT 0,
  audioBytes INTEGER NOT NULL DEFAULT 0,   -- quanto occupa in R2
  PRIMARY KEY (ownerId, shareId)
);
CREATE UNIQUE INDEX IF NOT EXISTS shares_token ON shares(token);
CREATE INDEX IF NOT EXISTS shares_note ON shares(ownerId, noteId);

-- Le sessioni: un token per dispositivo, aperto con un ID token di Google e valido finche' non
-- viene revocato. E' quello che l'app manda a ogni richiesta al posto dell'ID token, che dura un'ora.
CREATE TABLE IF NOT EXISTS sessions (
  token      TEXT    PRIMARY KEY,           -- 32 byte casuali, base64url
  ownerId    TEXT    NOT NULL,
  deviceId   TEXT    NOT NULL DEFAULT '',
  deviceName TEXT    NOT NULL DEFAULT '',
  email      TEXT,                          -- com'era nell'ID token: per riconoscere l'account nel pannello
  createdAt  INTEGER NOT NULL,
  lastSeenAt INTEGER NOT NULL,
  revokedAt  INTEGER                        -- NULL: viva
);
CREATE INDEX IF NOT EXISTS sessions_owner ON sessions(ownerId, revokedAt);

-- Gli ospiti del computer di casa: un token per persona, emesso dal proprietario e revocabile.
-- Il companion lo verifica qui prima di trascrivere, e qui riporta i secondi fatti.
CREATE TABLE IF NOT EXISTS guests (
  ownerId    TEXT    NOT NULL,
  guestId    TEXT    NOT NULL,
  name       TEXT    NOT NULL,
  token      TEXT    NOT NULL,             -- pg_ + 24 byte casuali
  createdAt  INTEGER NOT NULL,
  revokedAt  INTEGER,                      -- NULL: vivo
  lastUsedAt INTEGER,
  jobs       INTEGER NOT NULL DEFAULT 0,
  seconds    INTEGER NOT NULL DEFAULT 0,   -- di audio trascritto
  PRIMARY KEY (ownerId, guestId)
);
CREATE UNIQUE INDEX IF NOT EXISTS guests_token ON guests(token);

-- Il computer di casa di ogni account: gli indirizzi e il token del companion, cosi' che chi entra
-- con Google su un dispositivo nuovo non debba ricollegarlo a mano. Il token e' cifrato con
-- COMPUTER_KEY (un segreto del Worker, AES-GCM): senza la chiave la colonna resta NULL. Vince
-- l'ultimo che ha scritto, per updatedAt.
CREATE TABLE IF NOT EXISTS computers (
  ownerId     TEXT    PRIMARY KEY,
  url         TEXT    NOT NULL DEFAULT '',  -- l'indirizzo di casa, in LAN
  remoteUrl   TEXT    NOT NULL DEFAULT '',  -- quello che vale anche da fuori (Tailscale)
  name        TEXT    NOT NULL DEFAULT '',
  model       TEXT    NOT NULL DEFAULT '',
  tokenCipher TEXT,                         -- v1.<iv>.<cifrato>; NULL: nessun token custodito
  updatedAt   INTEGER NOT NULL,             -- l'orologio del dispositivo che l'ha scritto
  deviceId    TEXT    NOT NULL DEFAULT ''
);

-- Relay temporaneo privato. Mai scrivere archivedAt/state/owners.seq dal relay.
-- Riserva quota e idempotenza in un INSERT atomico. Anche gli upload incompleti scadono.
CREATE TABLE IF NOT EXISTS relay_transfers (
  transferId TEXT PRIMARY KEY,
  ownerId TEXT NOT NULL,
  requestId TEXT NOT NULL,
  requestedAt INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('audio', 'source')),
  entityId TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  bytes INTEGER NOT NULL CHECK(bytes > 0 AND bytes <= 268435456),
  mime TEXT NOT NULL,
  originalName TEXT NOT NULL,
  deviceId TEXT NOT NULL,
  deviceName TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  expiresAt INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('uploading', 'ready', 'deleting')),
  cleanupAttempts INTEGER NOT NULL DEFAULT 0,
  nextCleanupAt INTEGER NOT NULL DEFAULT 0,
  UNIQUE(ownerId, requestId)
);
CREATE INDEX IF NOT EXISTS relay_owner ON relay_transfers(ownerId, expiresAt);
CREATE INDEX IF NOT EXISTS relay_cleanup ON relay_transfers(nextCleanupAt, expiresAt);
CREATE TABLE IF NOT EXISTS relay_chunks (
  transferId TEXT NOT NULL REFERENCES relay_transfers(transferId) ON DELETE CASCADE,
  chunkIndex INTEGER NOT NULL CHECK(chunkIndex >= 0 AND chunkIndex < 32),
  bytes INTEGER NOT NULL CHECK(bytes > 0 AND bytes <= 8388608),
  sha256 TEXT NOT NULL,
  uploaded INTEGER NOT NULL DEFAULT 0 CHECK(uploaded IN (0, 1)),
  PRIMARY KEY(transferId, chunkIndex)
);
