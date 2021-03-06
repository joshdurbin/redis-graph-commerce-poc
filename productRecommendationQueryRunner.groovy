#!/usr/bin/env groovy

@Grapes([
  @Grab(group='org.apache.commons', module='commons-pool2', version='2.8.0'),
  @Grab(group='org.apache.commons', module='commons-math3', version='3.6.1'),
  @Grab(group='redis.clients', module='jedis', version='3.2.0'),
  @Grab(group='com.redislabs', module='jredisgraph', version='2.0.2'),
  @Grab(group='me.tongfei', module='progressbar', version='0.7.3'),
  @Grab(group='org.slf4j', module='slf4j-simple', version='1.7.30'),
  @Grab(group='com.github.oshi', module='oshi-core', version='4.6.1')
])

import com.redislabs.redisgraph.Statistics.Label
import com.redislabs.redisgraph.impl.api.RedisGraph
import groovy.transform.Canonical
import me.tongfei.progressbar.ProgressBar
import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import oshi.SystemInfo
import redis.clients.jedis.JedisPool
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

def progressBarUpdateInterval = 50

// defaults must be strings for CliBuilder
def defaultGraphDB = 'prodrec'
def defaultThreadCount = "${new SystemInfo().hardware.processor.physicalProcessorCount}"
def defaultRedisHost = 'localhost'
def defaultRedisPort = '6379'
def defaultTopNumberOfPurchasers = '5000'

def cli = new CliBuilder(header: 'Concurrent RedisGraph Query Runner', usage:'productRecommendationQueryRunner <args>', width: -1)
cli.db(longOpt: 'database', "The RedisGraph database to use for the query [defaults to ${defaultGraphDB}]", args: 1, defaultValue: defaultGraphDB)
cli.tc(longOpt: 'threadCount', "The thread count to use [defaults to ${defaultThreadCount}]", args: 1, defaultValue: defaultThreadCount)
cli.h(longOpt: 'help', 'Usage Information')
cli.rh(longOpt: 'redisHost', "The host of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to ${defaultRedisHost}]", args: 1, defaultValue: defaultRedisHost)
cli.rp(longOpt: 'redisPort', "The port of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to ${defaultRedisPort}]", args: 1, defaultValue: defaultRedisPort)
cli.tp(longOpt: 'topPurchasers', "The number of top purchasers to query for [defaults to ${defaultTopNumberOfPurchasers}]", args: 1, defaultValue: defaultTopNumberOfPurchasers)
cli.l(longOpt: 'limitResults', "The default results limit.", args: 1)

// parse and validate options
def cliOptions = cli.parse(args)

def printErr = System.err.&println

if (!cliOptions) {
  cli.usage()
  System.exit(-1)
}

if (cliOptions.help) {
  cli.usage()
  System.exit(0)
}

def db = cliOptions.db

// setup jedis and graph
def threadCount = cliOptions.tc as Integer
def config = new GenericObjectPoolConfig()
config.setMaxTotal(threadCount)
def jedisPool = new JedisPool(config, cliOptions.redisHost, cliOptions.redisPort as Integer)
def redisGraph = new RedisGraph(jedisPool)

// query to get the top 1,000 person ids with the most orders
def personIdsToOrderCounts = redisGraph.query(db, "match (p:person)-[:transact]->(o:order) return p.id, count(o) as orders order by orders desc limit ${cliOptions.topPurchasers}")
def personIds = personIdsToOrderCounts.collect {
  it.values.first() as Integer
}

// queue is used to track results coming back from the worker threads
def resultsQueue = new ConcurrentLinkedQueue()

// latch is used to denote to the progress bar when things should be complete
def latch = new CountDownLatch(threadCount)

@Canonical class RecommendedProducts {

  def personId
  def products
  def queryTime
}

@Canonical class Product {

  def id
  def name
}

// this is used to generate a reaslistic max value for the progressbar
def expectedNumberOfQueueEntries = personIds.size()
def queueOfPeopleToQueryForProductRecommendations = new ConcurrentLinkedQueue(personIds.shuffled())
def times = new SynchronizedDescriptiveStatistics()

new ProgressBar('Progress', expectedNumberOfQueueEntries, progressBarUpdateInterval).withCloseable { progressBar ->
  threadCount.times {
    Thread.start {
      while (!queueOfPeopleToQueryForProductRecommendations.isEmpty()) {
        def personId = queueOfPeopleToQueryForProductRecommendations.poll()
        try {
          // ask the graph for the product ids and names found in the placed orders of other users who share product purchase histories with a given user, person id
          def query = """match (p:person { id: ${personId} })-[:transact]->(:order)-[:contain]->(prod:product)
                       match (prod)<-[:contain]-(:order)-[:contain]->(rec_prod:product)
                       where not (p)-[:transact]->(:order)-[:contain]->(rec_prod)
                       return rec_prod.id, rec_prod.name order by indegree(prod) desc limit 10"""
//                       match (rec_prod)<-[r:rating]-(:person)
//                       return rec_prod.id, rec_prod.name order by AVG(r.rating) desc limit 10"""

          def recommendedProductsQuery = redisGraph.query(db, query)
          def recommendedProducts = recommendedProductsQuery.results.collect {
            new Product(it.values().first(), it.values().last())
          }

          // get the query details and offer them to the queue for reporting
          def queryTime = recommendedProductsQuery.statistics.getStringValue(Label.QUERY_INTERNAL_EXECUTION_TIME).takeBefore(' ')
          times.addValue(queryTime as Double)
          progressBar.step()

        } catch (Exception e ) {
          printErr("error processing ${personId}", e)
        }
      }

      latch.countDown()
    }
  }
  latch.await()
}

println "Query performance p50 ${(times.getPercentile(50.0) as String).takeBefore('.')}ms, p95 ${(times.getPercentile(95.0) as String).takeBefore('.')}ms, p99 ${(times.getPercentile(99.0) as String).takeBefore('.')}ms"
