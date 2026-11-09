package io.linkmc.core

import io.linkmc.api.LinkApi

import java.util.Objects
import java.util.concurrent.atomic.AtomicReference

/** Installed by Link bootstrap, shared with the plugin-facing ServiceLoader API. */
final class RuntimeProvider extends LinkApi {
  override def data(): LinkApi.Data = RuntimeProvider.runtime().data()
  override def sessions(): LinkApi.Sessions = RuntimeProvider.runtime().sessions()
  override def network(): LinkApi.Network = RuntimeProvider.runtime().network()
  override def worlds(): LinkApi.Worlds = RuntimeProvider.runtime().worlds()
  override def engine(): LinkApi.Engine = RuntimeProvider.runtime().engine()
}

object RuntimeProvider {
  private val RUNTIME = new AtomicReference[LinkApi]()

  def install(service: LinkApi): Unit =
    if (!RUNTIME.compareAndSet(null, Objects.requireNonNull(service))) throw new IllegalStateException("Link already initialized")

  def uninstall(service: LinkApi): Unit = { RUNTIME.compareAndSet(service, null); () }

  private def runtime(): LinkApi = {
    val service = RUNTIME.get()
    if (service == null) throw new IllegalStateException("Link runtime is disabled or unavailable")
    service
  }
}
