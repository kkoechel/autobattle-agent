-- AutoBattle Agent game -- coordinator schema.
--
-- Deliberately its own database on the subgames droplet, sharing nothing with
-- autobattle.online. The card catalogue is PULLED from the main game read-only
-- and lives in a file, not here, so nothing in this service can ever write to
-- the main game's data.

CREATE TABLE IF NOT EXISTS players (
  id          INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  handle      VARCHAR(32)  NOT NULL,
  -- SHA-256 of the bearer token. The token itself is shown once, at join, and
  -- then only the app holds it: a leaked database should not hand out logins.
  key_hash    CHAR(64)     NOT NULL,
  is_bot      TINYINT(1)   NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen   DATETIME     NULL,
  UNIQUE KEY uq_handle (handle),
  UNIQUE KEY uq_key (key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One deck per player. The game is "iterate YOUR agent's deck", so there is
-- nothing to disambiguate and a single row keeps the round-robin trivial.
CREATE TABLE IF NOT EXISTS decks (
  player_id   INT UNSIGNED NOT NULL PRIMARY KEY,
  name        VARCHAR(80)  NOT NULL,
  cards_json  MEDIUMTEXT   NOT NULL,   -- [{"card_id":n,"quantity":n}, ...]
  plan_json   MEDIUMTEXT   NOT NULL,
  revision    INT UNSIGNED NOT NULL DEFAULT 1,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                           ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_deck_player FOREIGN KEY (player_id) REFERENCES players(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rounds (
  id          INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  started_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME     NULL,
  n_decks     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  seeds       SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  note        VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS results (
  round_id    INT UNSIGNED NOT NULL,
  player_id   INT UNSIGNED NOT NULL,
  deck_name   VARCHAR(80)  NOT NULL,
  -- The deck revision that actually played, so a standing always points at a
  -- specific list rather than at whatever the player has since replaced.
  revision    INT UNSIGNED NOT NULL,
  wins        SMALLINT     NOT NULL DEFAULT 0,
  losses      SMALLINT     NOT NULL DEFAULT 0,
  draws       SMALLINT     NOT NULL DEFAULT 0,
  placement   SMALLINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (round_id, player_id),
  KEY idx_player (player_id),
  CONSTRAINT fk_res_round FOREIGN KEY (round_id) REFERENCES rounds(id)
    ON DELETE CASCADE,
  -- Results must die with their player. Without this, removing an account
  -- left its rows behind pointing at nothing: the standings query inner-joins
  -- players so the row disappeared from the API, but the placements it had
  -- already been assigned did not -- the table rendered 1..8, 10, 11 and
  -- looked broken to everyone else.
  CONSTRAINT fk_res_player FOREIGN KEY (player_id) REFERENCES players(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Bans are not in v1. The table exists so the rotating pool has somewhere to
-- land without a migration: insert to ban, and the oldest rows age out once
-- the pool is full.
CREATE TABLE IF NOT EXISTS bans (
  card_id     INT UNSIGNED NOT NULL PRIMARY KEY,
  banned_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reason      VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Starter decks handed to a new player at join.
--
-- A player whose first act is a cold archetype build comes last: the house
-- agents have been iterating for days, and the first phone-built deck scored
-- 1-3-3 against them. "Join, wait two minutes while your phone heats up,
-- come last" is a bad first five minutes. A starter puts them mid-table from
-- the moment they join, so training is about climbing rather than catching
-- up, and they are in the standings before they have done anything.
--
-- Deliberately a POOL, not one deck: handing every new player the identical
-- list would make the league a mirror and tell them nothing about their own
-- choices. Measured wins are stored so the pool can be kept honest -- a
-- starter that drifts to the top of the field is no longer a starter.
CREATE TABLE IF NOT EXISTS starters (
  id          INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(80)  NOT NULL,
  cards_json  MEDIUMTEXT   NOT NULL,
  plan_json   MEDIUMTEXT   NOT NULL,
  wins        DECIMAL(5,2) NOT NULL DEFAULT 0,
  opponents   SMALLINT     NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
