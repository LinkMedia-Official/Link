-- Read-only diagnostics. Connect to the Link database before running.
SELECT relname,n_live_tup AS estimated_live_rows,n_dead_tup AS estimated_dead_rows,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
       pg_size_pretty(pg_indexes_size(relid)) AS index_size
FROM pg_stat_user_tables WHERE left(relname,5)='link_'
ORDER BY pg_total_relation_size(relid) DESC;

SELECT id,version,octet_length(payload) AS current_bytes
FROM link_state ORDER BY octet_length(payload) DESC LIMIT 20;

-- Optional bounded sample: replace the ID with ONE ID returned above.
-- SELECT version,created_at,octet_length(payload) AS event_bytes
-- FROM link_events WHERE aggregate_id='native:node-a/world/link-spawn/0'
-- ORDER BY version DESC LIMIT 10;
