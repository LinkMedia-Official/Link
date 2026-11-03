CREATE TABLE IF NOT EXISTS link_locks(id char(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_schema (version integer PRIMARY KEY);
INSERT IGNORE INTO link_schema VALUES (1);

CREATE TABLE IF NOT EXISTS link_sessions (
 player uuid PRIMARY KEY, node varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, epoch bigint NOT NULL,
 state varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK (state IN ('ACTIVE','MOVING','RELEASED')),
 expires_at datetime(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_operations (
 id uuid PRIMARY KEY, digest longblob NOT NULL, created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_state (
 id varchar(512) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, version bigint NOT NULL, schema_version integer NOT NULL,
 payload longblob NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_events (
 aggregate_id varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, version bigint NOT NULL, operation uuid NOT NULL REFERENCES link_operations(id),
 schema_version integer NOT NULL, type varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, payload longblob NOT NULL,
 created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (aggregate_id, version)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_outbox (
 id uuid PRIMARY KEY REFERENCES link_operations(id), delivered boolean NOT NULL DEFAULT false
) ENGINE=InnoDB;
ALTER TABLE link_operations ADD COLUMN IF NOT EXISTS events_pruned boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS link_operations_retention_idx ON link_operations(events_pruned,created_at,id);
CREATE INDEX IF NOT EXISTS link_events_operation_idx ON link_events(operation);
CREATE INDEX IF NOT EXISTS link_outbox_delivery_idx ON link_outbox(delivered,id);
CREATE TABLE IF NOT EXISTS link_transfers (
 id uuid PRIMARY KEY, player uuid NOT NULL REFERENCES link_sessions(player), source_node varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_epoch bigint NOT NULL, destination_node varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, world varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL, final_version bigint NOT NULL,
 state varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK (state IN ('PREPARED','ACCEPTED','CANCELLED')), accepted_epoch bigint,
 created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
ALTER TABLE link_transfers ADD COLUMN IF NOT EXISTS link_one_prepared_transfer_key UUID GENERATED ALWAYS AS (CASE WHEN state='PREPARED' THEN player ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_prepared_transfer ON link_transfers(link_one_prepared_transfer_key);


CREATE TABLE IF NOT EXISTS link_nodes (
 id varchar(512) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, capacity integer NOT NULL CHECK(capacity > 0),
 heartbeat datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_channels (
 id varchar(512) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, node varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL REFERENCES link_nodes(id), world varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 capacity integer NOT NULL CHECK(capacity > 0), ready boolean NOT NULL DEFAULT false,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL, UNIQUE(node,world)
) ENGINE=InnoDB;
ALTER TABLE link_channels ADD COLUMN IF NOT EXISTS spawn_enabled boolean NOT NULL DEFAULT true;
CREATE TABLE IF NOT EXISTS link_reservations (
 id uuid PRIMARY KEY, player uuid NOT NULL, channel varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL REFERENCES link_channels(id),
 state varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(state IN ('RESERVED','ADMITTED')), expires_at datetime(6) NOT NULL, x double precision, y double precision, z double precision, yaw real, pitch real
) ENGINE=InnoDB;







ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS link_one_reservation_key UUID GENERATED ALWAYS AS (CASE WHEN state='RESERVED' THEN player ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_reservation ON link_reservations(link_one_reservation_key);
ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS link_one_admission_key UUID GENERATED ALWAYS AS (CASE WHEN state='ADMITTED' THEN player ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_admission ON link_reservations(link_one_admission_key);
CREATE TABLE IF NOT EXISTS link_warps (
 name varchar(512) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, node varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, world varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_templates (
 name varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, version integer NOT NULL CHECK(version > 0), payload longblob NOT NULL,
 sha256 longblob NOT NULL, created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(name,version)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS link_arrivals (
 id uuid PRIMARY KEY, player uuid NOT NULL, reservation uuid NOT NULL,
 transfer uuid, node varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, state varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(state IN ('ISSUED','CLAIMED','CANCELLED')),
 epoch bigint, expires_at datetime(6) NOT NULL,
 created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
ALTER TABLE link_arrivals ADD COLUMN IF NOT EXISTS link_one_issued_arrival_key UUID GENERATED ALWAYS AS (CASE WHEN state='ISSUED' THEN player ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_issued_arrival ON link_arrivals(link_one_issued_arrival_key);
CREATE TABLE IF NOT EXISTS link_routes (
 id uuid PRIMARY KEY, player uuid NOT NULL, reservation uuid NOT NULL,
 state varchar(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(state IN ('REQUESTED','READY','CONNECTING','COMPLETE','FAILED')),
 created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
ALTER TABLE link_routes ADD COLUMN IF NOT EXISTS link_one_route_request_key UUID GENERATED ALWAYS AS (CASE WHEN state='REQUESTED' THEN player ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_route_request ON link_routes(link_one_route_request_key);



CREATE TABLE IF NOT EXISTS link_player_names (player uuid PRIMARY KEY, name varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, lower_name varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL) ENGINE=InnoDB;
CREATE INDEX IF NOT EXISTS link_player_names_lookup_idx ON link_player_names(lower_name);
CREATE TABLE IF NOT EXISTS link_teleport_requests (
 id uuid PRIMARY KEY, requester uuid NOT NULL, recipient uuid NOT NULL,
 requester_epoch bigint NOT NULL, recipient_epoch bigint NOT NULL,
 here boolean NOT NULL, state varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at datetime(6) NOT NULL, created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 destination_node varchar(512) CHARACTER SET ascii COLLATE ascii_bin, destination_world varchar(512) CHARACTER SET ascii COLLATE ascii_bin,
 x double precision, y double precision, z double precision, yaw real, pitch real
) ENGINE=InnoDB;
CREATE INDEX IF NOT EXISTS link_tp_requester_idx ON link_teleport_requests(requester,created_at);
CREATE INDEX IF NOT EXISTS link_tp_recipient_idx ON link_teleport_requests(recipient,expires_at);
CREATE INDEX IF NOT EXISTS link_tp_expiry_idx ON link_teleport_requests(expires_at);
