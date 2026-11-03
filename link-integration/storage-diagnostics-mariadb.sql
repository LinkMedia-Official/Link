-- Read-only diagnostics. Select the Link database before running.
-- table_rows is an estimate; no player payload or credentials are returned.
SELECT table_name, table_rows,
       ROUND(data_length/1024/1024,2) AS data_mib,
       ROUND(index_length/1024/1024,2) AS index_mib,
       ROUND(data_free/1024/1024,2) AS reusable_mib
FROM information_schema.tables
WHERE table_schema=DATABASE() AND LEFT(table_name,5)='link_'
ORDER BY data_length+index_length DESC;

SELECT id,version,OCTET_LENGTH(payload) AS current_bytes
FROM link_state ORDER BY OCTET_LENGTH(payload) DESC LIMIT 20;

SHOW VARIABLES WHERE Variable_name IN ('log_bin','binlog_expire_logs_seconds','innodb_file_per_table');

-- Optional bounded sample: replace the ID with ONE ID returned above.
-- SELECT version,created_at,OCTET_LENGTH(payload) AS event_bytes
-- FROM link_events WHERE aggregate_id='native:node-a/world/link-spawn/0'
-- ORDER BY version DESC LIMIT 10;
