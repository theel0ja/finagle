package com.twitter.finagle.exp

import com.twitter.finagle._
import com.twitter.finagle.client.{StackClient, StdStackClient, DefaultPool, Transporter}
import com.twitter.finagle.exp.mysql._
import com.twitter.finagle.exp.mysql.transport.{MysqlTransporter, Packet}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.transport.Transport
import com.twitter.util.Duration

/**
 * Supplements a [[com.twitter.finagle.Client]] with convenient
 * builder methods for constructing a mysql client.
 */
trait MysqlRichClient { self: com.twitter.finagle.Client[Request, Result] =>
  /**
   * Creates a new `RichClient` connected to the logical
   * destination described by `dest` with the assigned
   * `label`. The `label` is used to scope client stats.
   */
  def newRichClient(dest: Name, label: String): mysql.Client =
    mysql.Client(newClient(dest, label))

  /**
   * Creates a new `RichClient` connected to the logical
   * destination described by `dest`.
   */
  def newRichClient(dest: String): mysql.Client =
    mysql.Client(newClient(dest))
}

/**
 * Tracing filter for mysql client requests.
 */
private object MysqlTracing extends SimpleFilter[Request, Result] { self =>
  def apply(request: Request, service: Service[Request, Result]) = {
    request match {
      case QueryRequest(sqlStatement) => Trace.recordBinary("mysql.query", sqlStatement)
      case PrepareRequest(sqlStatement) => Trace.recordBinary("mysql.prepare", sqlStatement)
      // TODO: save the prepared statement and put it in the executed request trace
      case ExecuteRequest(id, _, _, _) => Trace.recordBinary("mysql.execute", id)
      case _ => Trace.record("mysql." + request.getClass.getSimpleName.replace("$", ""))
    }
    service(request)
  }

  // TODO: We should consider adding toStackable(elem) to CanStackFrom so this sort of
  // boiler-plate isn't necessary. For example, we should be able to do MysqlTracing +: stack
  // and the role should be inferred (maybe as the class name).
  val role = Stack.Role("MysqlTracing")
  val module = new Stack.Simple[ServiceFactory[Request, Result]] {
    val role = MysqlTracing.role
    val description = "Trace mysql specific calls to the loaded tracer"
    def make(next: ServiceFactory[Request, Result])(implicit params: Stack.Params) =
      self andThen next
  }
}


/**
 * @example {{{
 * val client = Mysql.client
 *   .withCredentials("<username>", "<password>")
 *   .withDatabase("<db>")
 *   .newRichClient("inet!localhost:3306")
 * }}}
 */
object Mysql extends com.twitter.finagle.Client[Request, Result] with MysqlRichClient {

  /**
   * Implements a mysql client in terms of a
   * [[com.twitter.finagle.StackClient]]. The client inherits a wealth
   * of features from finagle including connection pooling and load
   * balancing.
   *
   * Additionally, this class provides methods for constructing a rich
   * client which exposes a rich mysql api.
   */
  case class Client(
    stack: Stack[ServiceFactory[Request, Result]] = MysqlTracing.module +: StackClient.newStack,
    params: Stack.Params = StackClient.defaultParams + DefaultPool.Param(
        low = 0, high = 1, bufferSize = 0,
        idleTime = Duration.Top,
        maxWaiters = Int.MaxValue)
  ) extends StdStackClient[Request, Result, Client] with MysqlRichClient {
    protected def copy1(
      stack: Stack[ServiceFactory[Request, Result]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    protected type In = Packet
    protected type Out = Packet
    protected def newTransporter() = MysqlTransporter(params)
    protected def newDispatcher(transport: Transport[Packet, Packet]):  Service[Request, Result] =
      mysql.ClientDispatcher(transport, Handshake(params))

    /**
     * The credentials to use when authenticating a new session.
     */
    def withCredentials(u: String, p: String): Client =
      configured(Handshake.Credentials(Option(u), Option(p)))

    /**
     * Database to use when this client establishes a new session.
     */
    def withDatabase(db: String): Client =
      configured(Handshake.Database(Option(db)))

    /**
     * The default character set used when establishing
     * a new session.
     */
    def withCharset(charset: Short): Client =
      configured(Handshake.Charset(charset))
  }

  val client = Client()

  def newClient(dest: Name, label: String): ServiceFactory[Request, Result] =
    client.newClient(dest, label)

  /**
   * The credentials to use when authenticating a new session.
   */
  @deprecated("Use client.withCredentials", "6.22.0")
  def withCredentials(u: String, p: String): Client =
    client.configured(Handshake.Credentials(Option(u), Option(p)))

  /**
   * Database to use when this client establishes a new session.
   */
  @deprecated("Use client.withDatabase", "6.22.0")
  def withDatabase(db: String): Client =
    client.configured(Handshake.Database(Option(db)))

  /**
   * The default character set used when establishing
   * a new session.
   */
  @deprecated("Use client.withCharset", "6.22.0")
  def withCharset(charset: Short): Client =
    client.configured(Handshake.Charset(charset))

  /**
   * A client configured with parameter p.
   */
  @deprecated("Use client.configured", "6.22.0")
  def configured[P: Stack.Param](p: P): Client =
    client.configured(p)
}
