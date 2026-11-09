package io.linkmc.core

import com.google.gson.JsonParser
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.signer.Aws4Signer
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.http.{SdkHttpFullRequest, SdkHttpMethod}
import software.amazon.awssdk.regions.Region

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.{HexFormat, UUID}
import scala.util.Using

/** SlimeVault 0.2: SigV4, path-style, writer lease, conditional PUT and exact revision GET. */
final class SlimeVaultStore(endpoint: String, region: String, bucket: String, prefix: String, accessKey: String, secretKey: String) extends WorldBlobs.Store {
  private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
  private val endpointUri: URI = URI.create(endpoint)
  if (!java.util.Set.of("http", "https").contains(this.endpointUri.getScheme()) || this.endpointUri.getHost() == null
    || this.endpointUri.getUserInfo() != null || this.endpointUri.getQuery() != null || this.endpointUri.getFragment() != null
    || !(this.endpointUri.getPath().isEmpty() || this.endpointUri.getPath().equals("/"))) throw new IllegalArgumentException("SlimeVault endpoint must be an HTTP(S) origin")
  if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || !prefix.matches("[A-Za-z0-9_/-]*") || prefix.contains("..")) throw new IllegalArgumentException("Invalid SlimeVault bucket or prefix")
  if (accessKey.isBlank() || secretKey.isBlank() || region.isBlank()) throw new IllegalArgumentException("Configure SlimeVault credentials and region")
  private val keyPrefix: String = if (prefix.isEmpty()) "" else prefix.replaceAll("/+$", "") + "/"
  private val signing: Aws4SignerParams = {
    val builder = Aws4SignerParams.builder()
    builder.awsCredentials(AwsBasicCredentials.create(accessKey, secretKey))
    builder.signingName("s3")
    builder.signingRegion(Region.of(region))
    builder.doubleUrlEncode(java.lang.Boolean.FALSE)
    builder.build()
  }

  @throws[Exception]
  private def request(method: String, bucket: String, key: String, revision: String, headers: java.util.Map[String, String], body: Array[Byte], maxBytes: Int): SlimeVaultStore.Reply = {
    val builder = SdkHttpFullRequest.builder().uri(endpointUri).method(SdkHttpMethod.fromValue(method))
      .encodedPath("/" + bucket + "/" + key).contentStreamProvider(() => new ByteArrayInputStream(body))
    headers.forEach((name: String, value: String) => { builder.putHeader(name, value); () })
    builder.putHeader("x-amz-content-sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)))
    if (revision != null) builder.putRawQueryParameter("versionId", revision)
    val signed = Aws4Signer.create().sign(builder.build(), signing)
    val http = HttpRequest.newBuilder(signed.getUri()).timeout(Duration.ofSeconds(60))
    signed.headers().forEach((name: String, values: java.util.List[String]) => {
      if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) values.forEach((value: String) => { http.header(name, value); () })
    })
    http.method(method, if (body.length == 0) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
    val response = client.send(http.build(), HttpResponse.BodyHandlers.ofInputStream())
    Using.resource(response.body()) { (input: InputStream) =>
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("SlimeVault " + method + " failed: HTTP " + response.statusCode())
      val bytes = input.readNBytes(maxBytes + 1)
      if (bytes.length > maxBytes) throw new IOException("SlimeVault response exceeds size limit")
      SlimeVaultStore.Reply(bytes, response.headers().map())
    }
  }
  @throws[Exception]
  private def control(key: String, operation: String, json: String): SlimeVaultStore.Reply =
    request("PUT", bucket, key, null, java.util.Map.of("x-slimevault-operation", operation, "Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8), 16384)
  @throws[Exception]
  override def put(world: Array[Byte]): WorldBlobs.Revision = {
    if (world.length == 0 || world.length > 512 * 1024 * 1024) throw new IllegalArgumentException("Invalid Slime world size")
    // Each upload has a new identity: failed SQL commits can leave harmless unreferenced objects.
    val key = keyPrefix + UUID.randomUUID() + ".slime"
    val acquired = control(key, "lease-acquire", "{\"ttl_ms\":300000}")
    val token = JsonParser.parseString(new String(acquired.body, StandardCharsets.UTF_8)).getAsJsonObject()
      .getAsJsonObject("State").getAsJsonObject("lease").get("token").getAsString()
    try {
      val saved = request("PUT", bucket, key, null, java.util.Map.of("x-slimevault-lease-token", token, "x-slimevault-if-revision", "none", "Content-Type", "application/octet-stream"), world, 16384)
      val version = saved.headers.getOrDefault("x-amz-version-id", java.util.List.of("")).getFirst()
      if (!version.matches("[0-9a-fA-F]{64}")) throw new IOException("SlimeVault did not confirm a revision")
      new WorldBlobs.Revision(bucket, key, version)
    } finally {
      // Bounded lease expires even when cleanup fails; never turn confirmed PUT into a retry.
      try { control(key, "lease-release", "{\"token\":" + new com.google.gson.Gson().toJson(token) + "}"); () } catch { case ignored: Exception => }
    }
  }
  @throws[Exception]
  override def get(revision: WorldBlobs.Revision): Array[Byte] = {
    if (!revision.bucket.equals(bucket) || !revision.key.startsWith(keyPrefix) || !revision.key.matches("[A-Za-z0-9_/-]+[.]slime")
      || !revision.version.matches("[0-9a-fA-F]{64}")) throw new IOException("SlimeVault reference does not match configured storage")
    request("GET", bucket, revision.key, revision.version, java.util.Map.of[String, String](), new Array[Byte](0), 512 * 1024 * 1024).body
  }
}

object SlimeVaultStore {
  private final case class Reply(body: Array[Byte], headers: java.util.Map[String, java.util.List[String]])
}
