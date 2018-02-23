package store.cassandra

import java.util.concurrent.Executor

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSource
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import domains.Key
import env.{CassandraConfig, DbDomainConfig}
import libs.streams.Flows
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import store.Result.{ErrorMessage, Result}
import store._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}

object CassandraJsonDataStore {
  def apply(cluster: Cluster,
            cassandraConfig: CassandraConfig,
            config: DbDomainConfig,
            actorSystem: ActorSystem): CassandraJsonDataStore = {
    val namespace = config.conf.namespace
    Logger.info(s"Load store Cassandra for namespace $namespace")
    new CassandraJsonDataStore(namespace, cassandraConfig.keyspace, cluster, actorSystem)
  }
}

class CassandraJsonDataStore(namespace: String, keyspace: String, cluster: Cluster, actorSystem: ActorSystem)
    extends JsonDataStore {

  private val namespaceFormatted = namespace.replaceAll(":", "_")

  import Cassandra._

  implicit private val mat = ActorMaterializer()(actorSystem)
  implicit private val c   = cluster

  import actorSystem.dispatcher

  Logger.info(s"Creating table ${keyspace}.$namespaceFormatted if not exists")
  cluster.connect().execute(s"""
      | CREATE TABLE IF NOT EXISTS $keyspace.$namespaceFormatted (
      |   namespace text,
      |   id text,
      |   key text,
      |   value text,
      |   PRIMARY KEY ((namespace), id)
      | )
      | """.stripMargin)

  cluster
    .connect()
    .execute(
      s"""
    | CREATE CUSTOM INDEX  IF NOT EXISTS ${keyspace}_${namespaceFormatted}_sasi ON $keyspace.$namespaceFormatted(key)  USING 'org.apache.cassandra.index.sasi.SASIIndex' WITH OPTIONS = {
    |   'analyzer_class' : 'org.apache.cassandra.index.sasi.analyzer.NonTokenizingAnalyzer',
    |   'case_sensitive' : 'false',
    |   'mode' : 'CONTAINS'
    | }
    """.stripMargin
    )

  private val regex = """[\*]?(([\w@\.0-9\-]+)(:?))+[\*]?""".r

  private def patternStatement(field: String, patterns: Seq[String]): Result[String] = {
    val hasError = patterns
      .map {
        case "*"       => false
        case regex(_*) => false
        case _         => true
      }
      .foldLeft(false)(_ || _)

    if (hasError) {
      Result.error("pattern.invalid")
    } else {
      Result.ok(
        patterns
          .collect {
            case str if str != "*" =>
              s" $field LIKE '${str.replaceAll("\\*", "%")}' "
          }
          .mkString(" OR ")
      )
    }

  }

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    session()
      .flatMap { implicit session =>
        getByIdWithSession(id)
          .map { d =>
            Logger.debug(s"data exists $d")
            Result.errors(ErrorMessage("error.data.exists", id.key))
          }
          .orElse(Source.fromFuture(createWithSession(id, data, None)))
          .runWith(Sink.head)
      }

  private def createWithSession(id: Key, data: JsValue, ttl: Option[FiniteDuration])(
      implicit session: Session
  ): Future[Result[JsValue]] = {

    val ttlValue = ttl.map(ttl => s" USING TTL ${ttl.toSeconds}").getOrElse("")
    val query =
      s"INSERT INTO $keyspace.$namespaceFormatted (namespace, id, key, value) values (?, ?, ?, ?) IF NOT EXISTS $ttlValue  "

    val args = Seq(namespaceFormatted, id.key, id.key, Json.stringify(data))
    executeWithSession(
      query,
      args: _*
    ).map { _ =>
      Result.ok(data)
    }
  }

  override def update(oldId: Key, id: Key, data: JsValue): Future[Result[JsValue]] =
    session()
      .flatMap { session =>
        updateWithSession(oldId, id, data, None)(session)
      }

  private def updateWithSession(oldId: Key, id: Key, data: JsValue, ttl: Option[FiniteDuration])(
      implicit session: Session
  ): Future[Result[JsValue]] = if (oldId.key == id.key) {
    updateInCassandra(id: Key, data, ttl)
  } else {
    deleteWithSession(oldId)
      .flatMap{ _ =>
        createWithSession(id, data, ttl)
      }
  }

  private def updateInCassandra(id: Key, data: JsValue, ttl: Option[FiniteDuration])(
      implicit session: Session
  ): Future[Result[JsValue]] = {

    val ttlValue = ttl.map(ttl => s" USING TTL ${ttl.toSeconds}").getOrElse("")
    val query =
      s"UPDATE $keyspace.$namespaceFormatted $ttlValue SET value = ? WHERE namespace = ? AND id = ? IF EXISTS "
    val args = Seq(Json.stringify(data), namespaceFormatted, id.key)
    executeWithSession(query, args: _*).map(rs => Result.ok(data))
  }

  override def delete(id: Key): Future[Result[JsValue]] =
    session().flatMap(deleteWithSession(id)(_))

  private def deleteWithSession(id: Key)(implicit session: Session): Future[Result[JsValue]] =
    getByIdWithSession(id)(session)
      .mapAsync(1) { data =>
        val query =
          s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
        val args = Seq(namespaceFormatted, id.key)
        executeWithSession(
          query,
          args: _*
        ).map{r =>
          Result.ok(data)
        }
      }
      .runWith(Sink.headOption)
      .map {
        _.getOrElse(Result.error("error.data.missing"))
      }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    session()
      .flatMap { implicit s =>
        deleteAllWithSession(patterns)
      }

  def deleteAllWithSession(patterns: Seq[String])(implicit session: Session): Future[Result[Done]] =
    if (patterns.isEmpty || patterns.contains("")) {
      FastFuture.successful(Result.ok(Done))
    } else {
      patternStatement("key", patterns) match {
        case Right(s) =>
          val stm = if (!s.isEmpty) s"AND $s" else ""
          val query =
            s"SELECT namespace, id, value FROM $keyspace.$namespaceFormatted WHERE namespace = ? $stm"
          Logger.debug(s"Running query $query with args [$namespaceFormatted]")
          val stmt =
            new SimpleStatement(query, namespaceFormatted).setFetchSize(200)
          CassandraSource(stmt)
            .map { rs =>
              (rs.getString("namespace"), rs.getString("id"))
            }
            .mapAsync(4) {
              case (n, id) =>
                val query =
                  s"DELETE FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? IF EXISTS "
                val args = Seq(n, id)
                executeWithSession(query, n, id)
            }
            .runFold(0)((acc, _) => acc + 1)
            .map(_ => Result.ok(Done))
        case Left(s) => FastFuture.successful(Result.error(s))
      }
    }

  override def getById(id: Key): FindResult[JsValue] =
    SourceFindResult(
      Source
        .fromFuture(session())
        .flatMapConcat { session =>
          getByIdWithSession(id)(session)
        }
    )

  private def getByIdWithSession(id: Key)(implicit session: Session): Source[JsValue, NotUsed] = {
    val query =
      s"SELECT value FROM $keyspace.$namespaceFormatted WHERE namespace = ? AND id = ? "
    val args = Seq(namespaceFormatted, id.key)
    Logger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")}")
    val stmt =
      new SimpleStatement(query, args: _*)
        .setKeyspace("izanami")

    CassandraSource(stmt).map { rs =>
      Json.parse(rs.getString("value"))
    }
  }

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[JsValue]] =
    Source
      .fromFuture(session())
      .flatMapConcat { s =>
        getByIdLikeWithSession(patterns)(s)
          .via(Flows.count {
            Flow[JsValue]
              .drop(nbElementPerPage * (page - 1))
              .take(nbElementPerPage)
              .fold(Seq.empty[JsValue])(_ :+ _)
          })

      }
      .map {
        case (res, count) =>
          DefaultPagingResult(res, page, nbElementPerPage, count)
      }
      .runWith(Sink.head)

  override def getByIdLike(patterns: Seq[String]): FindResult[JsValue] =
    SourceFindResult(
      Source
        .fromFuture(session())
        .flatMapConcat { implicit session =>
          getByIdLikeWithSession(patterns)
        }
    )

  private def getByIdLikeWithSession(patterns: Seq[String])(implicit session: Session): Source[JsValue, NotUsed] =
    if (patterns.isEmpty || patterns.contains("")) {
      Source.empty
    } else {
      patternStatement("key", patterns) match {
        case Right(s) =>
          val stm = if (!s.isEmpty) s"AND $s" else ""
          val query =
            s"SELECT value FROM $keyspace.$namespaceFormatted WHERE namespace = ? $stm"
          Logger.debug(s"Running query $query with args [$namespaceFormatted]")
          val stmt = new SimpleStatement(query, namespaceFormatted)
          CassandraSource(stmt).map { rs =>
            Json.parse(rs.getString("value"))
          }
        case Left(err) =>
          Source.failed(new IllegalArgumentException("pattern.invalid"))
      }
    }

  override def count(patterns: Seq[String]): Future[Long] =
    session().flatMap(countWithSession(patterns)(_).flatMap {
      case Right(r) => FastFuture.successful(r)
      case Left(r) =>
        FastFuture.failed(new IllegalArgumentException("pattern.invalid"))
    })

  private def countWithSession(patterns: Seq[String])(implicit session: Session): Future[Result[Long]] =
    if (patterns.isEmpty || patterns.contains("")) {
      FastFuture.successful(Result.ok(0))
    } else {
      patternStatement("key", patterns) match {
        case Right(s) =>
          val stm = if (!s.isEmpty) s"AND $s" else ""
          val query =
            s"SELECT COUNT(*) AS count FROM $keyspace.$namespaceFormatted WHERE namespace = ? $stm"
          Logger.debug(s"Running query $query with args [$namespaceFormatted]")
          val stmt =
            new SimpleStatement(
              query,
              namespaceFormatted
            ).setKeyspace("izanami")
          CassandraSource(stmt)
            .map { rs =>
              Result.ok(rs.getLong("count"))
            }
            .runWith(Sink.head)
        case Left(err) => FastFuture.successful(Result.error(err))
      }
    }
}

object Cassandra {

  def session()(implicit cluster: Cluster, ec: ExecutionContext): Future[Session] =
    cluster.connectAsync().toFuture

  def executeWithSession(query: String, args: Any*)(implicit session: Session,
                                                    ec: ExecutionContext): Future[ResultSet] =
    if (args.isEmpty) {
      Logger.debug(s"Running query $query ")
      session.executeAsync(query).toFuture
    } else {
      Logger.debug(s"Running query $query with args ${args.mkString("[", ",", "]")} ")
      session
        .executeAsync(new SimpleStatement(query, args.map(_.asInstanceOf[Object]): _*))
        .toFuture
    }

  def executeWithSession(query: Statement)(implicit session: Session, ec: ExecutionContext): Future[ResultSet] =
    session.executeAsync(query).toFuture

  implicit class FutureConvertions[T](f: ListenableFuture[T]) {
    def toFuture(implicit ec: ExecutionContext): Future[T] = {

      val promise = Promise[T]
      Futures.addCallback(
        f,
        new FutureCallback[T] {
          override def onFailure(t: Throwable) = promise.failure(t)
          override def onSuccess(result: T)    = promise.success(result)
        },
        ExecutorConversions.toExecutor(ec)
      )

      promise.future
    }

  }

  object ExecutorConversions {
    def toExecutor(ec: ExecutionContext): Executor =
      new Executor {
        override def execute(command: Runnable): Unit = ec.execute(command)
      }
  }
}
