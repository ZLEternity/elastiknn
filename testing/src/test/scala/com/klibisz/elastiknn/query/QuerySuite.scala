package com.klibisz.elastiknn.query

import com.klibisz.elastiknn.KNearestNeighborsQuery.{IndexedQueryVector, QueryOptions}
import com.klibisz.elastiknn.ProcessorOptions.ModelOptions
import com.klibisz.elastiknn.client.ElastiKnnClient
import com.klibisz.elastiknn._
import com.sksamuel.elastic4s.ElasticDsl
import com.sksamuel.elastic4s.requests.common.RefreshPolicy.Immediate
import com.sksamuel.elastic4s.requests.mappings.{BasicField, MappingDefinition}
import com.sksamuel.elastic4s.requests.searches.SearchResponse
import io.circe.parser.decode
import org.scalatest.{Assertion, AsyncTestSuite}

import scala.concurrent.Future
import scala.util.Try

trait QuerySuite extends ElasticAsyncClient with ElasticDsl {

  this: AsyncTestSuite =>

  protected def readTestData(sim: Similarity, dim: Int): Try[TestData] =
    for {
      rawJson <- Try {
        val name = s"${sim.name.toLowerCase}-$dim.json"
        val src = scala.io.Source.fromResource(name)
        try src.mkString
        finally src.close()
      }
      dec <- decode[TestData](rawJson).toTry
    } yield dec

  final def testDataDims: Seq[Int] = Seq(10, 128, 512)

  final class Support(rawField: String, sim: Similarity, dim: Int, modelOptions: ModelOptions) {

    val index: String = s"test-${sim.name.toLowerCase}-${System.currentTimeMillis()}"
    val pipelineId: String = s"$index-pipeline"
    val popts: ProcessorOptions = ProcessorOptions(rawField, dim, modelOptions)
    val mapDef: MappingDefinition = MappingDefinition(Seq(BasicField(rawField, "elastiknn_vector")))
    val eknn: ElastiKnnClient = new ElastiKnnClient()
    val queryVectorIdPrefix: String = "q"
    val corpusVectorIdPrefix: String = "c"

    private def corpusId(i: Int): String = s"$corpusVectorIdPrefix$i"
    private def queryId(i: Int): String = s"$queryVectorIdPrefix$i"

    private lazy val setupIndexCorpus: Future[TestData] = for {
      testData <- Future.fromTry(readTestData(sim, dim))
      _ <- client.execute(deleteIndex(index))
      _ <- eknn.setupCluster()
      _ <- eknn.createPipeline(pipelineId, popts)
      _ <- client.execute(createIndex(index).mapping(mapDef))
      corpusIds = testData.corpus.indices.map(corpusId)
      _ <- eknn.indexVectors(index, pipelineId, rawField, testData.corpus, Some(corpusIds), Immediate)
    } yield testData

    private lazy val numHits: Future[Int] = for (testData <- setupIndexCorpus)
      yield testData.queries.head.similarities.length

    /** Runs a test for queries which just take an [[ElastiKnnVector]]. Passes the query and response to an assertion. */
    def testGiven(queryOptions: QueryOptions)(fun: Seq[(Query, SearchResponse)] => Assertion): Future[Assertion] =
      for {
        testData <- setupIndexCorpus
        numHits <- this.numHits
        queriesResponses <- Future.sequence(testData.queries.map { q =>
          queryOptions match {
            case QueryOptions.Exact(opts) => eknn.knnQuery(index, opts, q.vector, numHits).map(r => q -> r)
            case QueryOptions.Lsh(opts)   => eknn.knnQuery(index, opts, q.vector, numHits).map(r => q -> r)
            case _                        => Future.failed(illArgEx("query options must be exact or lsh"))
          }
        })
      } yield fun(queriesResponses)

    /** Run tests for queries which take an [[IndexedQueryVector]]. Passes the query, query vector ID, and response to an assertion. */
    def testIndexed(queryOptions: QueryOptions)(fun: Seq[(Query, String, SearchResponse)] => Assertion): Future[Assertion] =
      for {
        testData <- setupIndexCorpus
        numHits <- numHits.map(_ + testData.queries.length + 1)
        queryIds = testData.queries.indices.map(queryId)
        _ <- eknn.indexVectors(index, pipelineId, popts.fieldRaw, testData.queries.map(_.vector), ids = Some(queryIds), Immediate)
        queriesAndResponses <- Future.sequence(testData.queries.zipWithIndex.map {
          case (q, i) =>
            queryOptions match {
              case QueryOptions.Exact(opts) =>
                eknn.knnQuery(index, opts, IndexedQueryVector(index, rawField, queryId(i)), numHits).map(r => (q, queryId(i), r))
            }
        })
      } yield fun(queriesAndResponses)

  }

}