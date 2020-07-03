#!/usr/bin/env groovy

@Grapes([
  @Grab(group='org.apache.commons', module='commons-math3', version='3.6.1'),
  @Grab(group='org.neo4j.driver', module='neo4j-java-driver', version='4.0.2'),
  @Grab(group='me.tongfei', module='progressbar', version='0.7.3'),
  @Grab(group='com.google.guava', module='guava', version='29.0-jre'),
  @Grab(group='org.slf4j', module='slf4j-simple', version='1.7.30'),
  @Grab(group='com.github.oshi', module='oshi-core', version='4.6.1')
])

import groovy.transform.Canonical
import me.tongfei.progressbar.ProgressBar
import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics
import oshi.SystemInfo
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import com.google.common.base.Stopwatch

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
def neo4jdb = GraphDatabase.driver('neo4j://localhost', AuthTokens.basic( 'neo4j', 'natoar23ae' ))

// query to get the top 1,000 person ids with the most orders

def personIds = []

neo4jdb.session().withCloseable { session ->

  def thing = session.run("match (p:person)-[:transact]->(o:order) return p.id, count(o) as orders order by orders desc limit ${cliOptions.topPurchasers}")
  thing.each {
    personIds << (it.get('p.id').asInt())
  }
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
        neo4jdb.session().withCloseable { session ->

          try {
            // ask the graph for the product ids and names found in the placed orders of other users who share product purchase histories with a given user, person id

            def query = """match (p:person { id: ${personId} })-[:transact]->(:order)-[:contain]->(prod:product)
                         match (prod)<-[:contain]-(:order)-[:contain]->(rec_prod:product)
                         where not (p)-[:transact]->(:order)-[:contain]->(rec_prod)
                         return rec_prod.id, rec_prod.name order by size((prod)<-[]-()) desc limit 10"""

            def watch = Stopwatch.createStarted()
            session.run(query)
            watch.stop()
            times.addValue(watch.elapsed(TimeUnit.MICROSECONDS ) as Double)
            progressBar.step()

          } catch (Exception e ) {
            printErr("error processing ${personId}", e)
          }
        }
      }

      latch.countDown()
    }
  }
  latch.await()
}

println "Query performance p50 ${(times.getPercentile(50.0) as String).takeBefore('.')}, p95 ${(times.getPercentile(95.0) as String).takeBefore('.')}, p99 ${(times.getPercentile(99.0) as String).takeBefore('.')} micro seconds "

neo4jdb.close()