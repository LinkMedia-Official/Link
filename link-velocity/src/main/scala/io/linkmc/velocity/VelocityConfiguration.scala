package io.linkmc.velocity

import java.io.IOException
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import java.util.Map as JMap
import org.yaml.snakeyaml.{LoaderOptions, Yaml}
import org.yaml.snakeyaml.constructor.SafeConstructor
import scala.util.Using

/** Credentials are neither logged nor copied back into the user's configuration. */
final class VelocityConfiguration private (jdbcUrlValue: String, usernameValue: String, passwordValue: String) {
  private[velocity] def jdbcUrl(): String = jdbcUrlValue
  private[velocity] def username(): String = usernameValue
  private[velocity] def password(): String = passwordValue
}

object VelocityConfiguration {
  private[velocity] def load(directory: Path, environment: JMap[String, String]): VelocityConfiguration = {
    val root: JMap[?, ?] =
      try {
        Files.createDirectories(directory)
        val file = directory.resolve("config.yml")
        try Files.writeString(file, DEFAULTS, StandardOpenOption.CREATE_NEW)
        catch { case _: FileAlreadyExistsException => () }
        val options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setCodePointLimit(1024 * 1024)
        Using.resource(Files.newBufferedReader(file)) { reader =>
          new Yaml(new SafeConstructor(options)).load[AnyRef](reader) match {
            case mapping: JMap[?, ?] => mapping
            case _ => throw new IllegalArgumentException()
          }
        }
      } catch {
        // YAML parser errors may quote lines containing credentials.
        case _: IOException | _: RuntimeException =>
          throw new IllegalStateException("Cannot read plugins/link/config.yml; check YAML syntax and file permissions")
      }
    val database: JMap[?, ?] = root.get("database") match {
      case section: JMap[?, ?] => section
      case _ => throw new IllegalArgumentException("Configure database in plugins/link/config.yml")
    }
    val url = value(database, environment, "jdbc-url", "LINK_JDBC_URL")
    val user = value(database, environment, "username", "LINK_DB_USER")
    val password = value(database, environment, "password", "LINK_DB_PASSWORD")
    if ((!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:mariadb:")) || user.isBlank || password.isBlank)
      throw new IllegalArgumentException("Configure database.jdbc-url, username and password in plugins/link/config.yml, then restart Velocity")
    new VelocityConfiguration(url, user, password)
  }

  private def value(database: JMap[?, ?], environment: JMap[String, String], key: String, variable: String): String = {
    if (environment.containsKey(variable)) return environment.get(variable)
    database.get(key) match {
      case text: String => text
      case _ => throw new IllegalArgumentException("database." + key + " must be a quoted string in plugins/link/config.yml")
    }
  }

  private val DEFAULTS =
    """# Link Velocity configuration. Edit this file and restart Velocity.
      |# Use the SAME database as all Link backends.
      |# LINK_JDBC_URL, LINK_DB_USER and LINK_DB_PASSWORD override these values.
      |# Backend lists and modern forwarding remain in velocity.toml.
      |database:
      |  # PostgreSQL is also supported: jdbc:postgresql://127.0.0.1:5432/link
      |  jdbc-url: 'jdbc:mariadb://127.0.0.1:3306/link'
      |  username: 'link'
      |  password: ''
      |""".stripMargin
}
