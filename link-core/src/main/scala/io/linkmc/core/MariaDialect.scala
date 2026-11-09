package io.linkmc.core

/** Translation of the finite set of Link store statements; not a general SQL parser. */
private[core] object MariaDialect {
  private[core] def translate(sql: String): String = {
    var value = sql.replace("clock_timestamp()", "CURRENT_TIMESTAMP(6)")
      .replace("starts_with(id,?)", "LOCATE(?,id)=1")
      .replace("?*interval '1 second'", "INTERVAL ? SECOND")
      .replace("interval '1 second'", "INTERVAL 1 SECOND")
      .replace("interval '15 seconds'", "INTERVAL 15 SECOND")
      .replace("interval '30 seconds'", "INTERVAL 30 SECOND")
      .replace("'infinity'", "'9999-12-31 23:59:59.999999'")
      .replace("FOR UPDATE OF r", "FOR UPDATE")
      .replace("count(r.id) FILTER(WHERE r.state='ADMITTED')", "count(CASE WHEN r.state='ADMITTED' THEN r.id END)")
      .replace("count(DISTINCT r.player) FILTER(WHERE r.state='ADMITTED')", "count(DISTINCT CASE WHEN r.state='ADMITTED' THEN r.player END)")
      .replace("DELETE FROM link_reservations r USING link_channels c WHERE", "DELETE r FROM link_reservations r JOIN link_channels c WHERE")
    value = value.replaceAll("ON CONFLICT\\([a-z_]+\\) DO UPDATE SET", "ON DUPLICATE KEY UPDATE")
    value.replaceAll("excluded\\.([a-z_]+)", "VALUES($1)")
  }
}
