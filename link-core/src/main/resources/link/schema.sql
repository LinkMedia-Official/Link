CREATE TABLE IF NOT EXISTS link_schema (version integer PRIMARY KEY);
INSERT INTO link_schema VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS link_sessions (
 player uuid PRIMARY KEY, node text NOT NULL, epoch bigint NOT NULL,
 state text NOT NULL CHECK (state IN ('ACTIVE','MOVING','RELEASED')),
 expires_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS link_operations (
 id uuid PRIMARY KEY, digest bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS link_state (
 id text PRIMARY KEY, version bigint NOT NULL, schema_version integer NOT NULL,
 payload bytea NOT NULL
);
CREATE TABLE IF NOT EXISTS link_events (
 aggregate_id text NOT NULL, version bigint NOT NULL, operation uuid NOT NULL REFERENCES link_operations(id),
 schema_version integer NOT NULL, type text NOT NULL, payload bytea NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (aggregate_id, version)
);
CREATE TABLE IF NOT EXISTS link_outbox (
 id uuid PRIMARY KEY REFERENCES link_operations(id), delivered boolean NOT NULL DEFAULT false
);
ALTER TABLE link_operations ADD COLUMN IF NOT EXISTS events_pruned boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS link_operations_retention_idx ON link_operations(events_pruned,created_at,id);
CREATE INDEX IF NOT EXISTS link_events_operation_idx ON link_events(operation);
CREATE INDEX IF NOT EXISTS link_outbox_delivery_idx ON link_outbox(delivered,id);
CREATE TABLE IF NOT EXISTS link_transfers (
 id uuid PRIMARY KEY, player uuid NOT NULL REFERENCES link_sessions(player), source_node text NOT NULL,
 source_epoch bigint NOT NULL, destination_node text NOT NULL, world text NOT NULL,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL, final_version bigint NOT NULL,
 state text NOT NULL CHECK (state IN ('PREPARED','ACCEPTED')), accepted_epoch bigint,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX IF NOT EXISTS link_one_prepared_transfer ON link_transfers(player) WHERE state='PREPARED';
ALTER TABLE link_transfers DROP CONSTRAINT IF EXISTS link_transfers_state_check;
ALTER TABLE link_transfers ADD CONSTRAINT link_transfers_state_check CHECK(state IN ('PREPARED','ACCEPTED','CANCELLED'));
CREATE TABLE IF NOT EXISTS link_nodes (
 id text PRIMARY KEY, capacity integer NOT NULL CHECK(capacity > 0),
 heartbeat timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS link_channels (
 id text PRIMARY KEY, node text NOT NULL REFERENCES link_nodes(id), world text NOT NULL,
 capacity integer NOT NULL CHECK(capacity > 0), ready boolean NOT NULL DEFAULT false,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL, UNIQUE(node,world)
);
ALTER TABLE link_channels ADD COLUMN IF NOT EXISTS spawn_enabled boolean NOT NULL DEFAULT true;
CREATE TABLE IF NOT EXISTS link_reservations (
 id uuid PRIMARY KEY, player uuid NOT NULL, channel text NOT NULL REFERENCES link_channels(id),
 state text NOT NULL CHECK(state IN ('RESERVED','ADMITTED')), expires_at timestamptz NOT NULL
);
ALTER TABLE link_reservations DROP CONSTRAINT IF EXISTS link_reservations_player_key;
ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS x double precision;
ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS y double precision;
ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS z double precision;
ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS yaw real;
ALTER TABLE link_reservations ADD COLUMN IF NOT EXISTS pitch real;
UPDATE link_reservations r SET x=c.x,y=c.y,z=c.z,yaw=c.yaw,pitch=c.pitch FROM link_channels c WHERE r.channel=c.id AND r.x IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_reservation ON link_reservations(player) WHERE state='RESERVED';
CREATE UNIQUE INDEX IF NOT EXISTS link_one_admission ON link_reservations(player) WHERE state='ADMITTED';
CREATE TABLE IF NOT EXISTS link_warps (
 name text PRIMARY KEY, node text NOT NULL, world text NOT NULL,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL
);
CREATE TABLE IF NOT EXISTS link_templates (
 name text NOT NULL, version integer NOT NULL CHECK(version > 0), payload bytea NOT NULL,
 sha256 bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(name,version)
);
CREATE TABLE IF NOT EXISTS link_arrivals (
 id uuid PRIMARY KEY, player uuid NOT NULL, reservation uuid NOT NULL,
 transfer uuid, node text NOT NULL, state text NOT NULL CHECK(state IN ('ISSUED','CLAIMED','CANCELLED')),
 epoch bigint, expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX IF NOT EXISTS link_one_issued_arrival ON link_arrivals(player) WHERE state='ISSUED';
CREATE TABLE IF NOT EXISTS link_routes (
 id uuid PRIMARY KEY, player uuid NOT NULL, reservation uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('REQUESTED','READY','FAILED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX IF NOT EXISTS link_one_route_request ON link_routes(player) WHERE state='REQUESTED';
ALTER TABLE link_routes DROP CONSTRAINT IF EXISTS link_routes_state_check;
ALTER TABLE link_routes ADD CONSTRAINT link_routes_state_check CHECK(state IN ('REQUESTED','READY','CONNECTING','COMPLETE','FAILED'));

CREATE TABLE IF NOT EXISTS link_player_names (player uuid PRIMARY KEY, name text NOT NULL, lower_name text NOT NULL);
CREATE INDEX IF NOT EXISTS link_player_names_lookup_idx ON link_player_names(lower_name);
CREATE TABLE IF NOT EXISTS link_teleport_requests (
 id uuid PRIMARY KEY, requester uuid NOT NULL, recipient uuid NOT NULL,
 requester_epoch bigint NOT NULL, recipient_epoch bigint NOT NULL,
 here boolean NOT NULL, state text NOT NULL,
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 destination_node text, destination_world text,
 x double precision, y double precision, z double precision, yaw real, pitch real
);
CREATE INDEX IF NOT EXISTS link_tp_requester_idx ON link_teleport_requests(requester,created_at);
CREATE INDEX IF NOT EXISTS link_tp_recipient_idx ON link_teleport_requests(recipient,expires_at);
CREATE INDEX IF NOT EXISTS link_tp_expiry_idx ON link_teleport_requests(expires_at);
