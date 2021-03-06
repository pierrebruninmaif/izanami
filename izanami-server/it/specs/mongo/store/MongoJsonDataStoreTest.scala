package specs.mongo.store

import env.{DbDomainConfig, DbDomainConfigDetails, Mongo}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.Configuration
import play.modules.reactivemongo.DefaultReactiveMongoApi
import reactivemongo.api.MongoConnection
import store.AbstractJsonDataStoreTest
import test.FakeApplicationLifecycle

import scala.concurrent.duration.DurationLong
import scala.concurrent.Await
import scala.util.Random
import store.mongo.MongoJsonDataStore

class MongoJsonDataStoreTest extends AbstractJsonDataStoreTest("Mongo") with BeforeAndAfter with BeforeAndAfterAll {

  val mongoApi = new DefaultReactiveMongoApi(
    Await.result(MongoConnection.fromString("mongodb://localhost:27017"), 5.seconds),
    s"dbtest-${Random.nextInt(50)}",
    false,
    Configuration.empty,
    new FakeApplicationLifecycle()
  )

  override def dataStore(name: String): MongoJsonDataStore = MongoJsonDataStore(
    mongoApi,
    DbDomainConfig(Mongo, DbDomainConfigDetails(name, None), None)
  )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAllData
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    deleteAllData
  }

  private def deleteAllData =
    Await.result(mongoApi.database.flatMap { _.drop() }, 30.seconds)

}
