#!/usr/bin/env groovy

@Grapes([
  @Grab(group='com.github.javafaker', module='javafaker', version='1.0.2'),
  @Grab(group='org.apache.commons', module='commons-lang3', version='3.9'),
  @Grab(group='org.neo4j.driver', module='neo4j-java-driver', version='4.0.2'),
  @Grab(group='com.github.oshi', module='oshi-core', version='4.6.1'),
  @Grab(group='me.tongfei', module='progressbar', version='0.7.3'),
  @Grab(group='org.slf4j', module='slf4j-simple', version='1.7.30'),
])

import com.github.javafaker.Faker
import groovy.transform.Canonical
import groovy.transform.Sortable
import me.tongfei.progressbar.ProgressBar
import oshi.SystemInfo

import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase

def progressBarUpdateInterval = 50

// defaults must be strings for CliBuilder
def defaultThreadCount = "${new SystemInfo().hardware.processor.physicalProcessorCount}"
def defaultMaxPotentialViews = '25'
def defaultMinPotentialViews = '0'
def defaultPercentageOfViewsToAddToCart = '25'
def defaultPercentageOfAddToCartToPurchase = '90'
def defaultPercentageOfPurchasesToRate = '35'
def defaultMaxRandomTimeFromViewToAddToCartInMinutes = '4320'
def defaultMaxRandomTimeFromAddToCartToPurchased = '4320'
def defaultMaxPastDate = "${365 * 20}"
def defaultNumberOfPeopleToCreate = '5000'
def defaultNumberOfProductsToCreate = '1000'
def defaultNodeCreationBatchSize = '500'
def defaultMaxTaxRate = '0.125'
def defaultMinTaxRate = '0.0'
def defaultMaxShipRate = '0.15'
def defaultMinShipRate = '0.0'
def defaultMaxPerProductPrice = '1000.00'
def defaultMinPerProductPrice = '0.99'
def defaultNeo4jUri = 'neo4j://localhost'
def defaultNeo4jUsername = 'neo4j'
def defaultNeo4jPassword = 'heO2thoDac0ZtbJDAY'


def cli = new CliBuilder(header: 'Commerce Graph Generator', usage:'generateCommerceGraphNeo4j', width: -1)
cli.maxv(longOpt: 'maxPotentialViews', "The max number of potential views a person can have against a product [defaults to ${defaultMaxPotentialViews}]", args: 1, defaultValue: defaultMaxPotentialViews)
cli.minv(longOpt: 'minPotentialViews', "The min number of potential views a person can have against a product [defaults to ${defaultMinPotentialViews}]", args: 1, defaultValue: defaultMinPotentialViews)
cli.peratc(longOpt: 'percentageOfViewsToAddToCart', "The percentage of views to turn into add-to-cart events for a given person and product [defaults to ${defaultPercentageOfViewsToAddToCart}]", args: 1, defaultValue: defaultPercentageOfViewsToAddToCart)
cli.perpur(longOpt: 'percentageOfAddToCartToPurchase', "The percentage of add-to-cart into purchase events for a given person and product [defaults to ${defaultPercentageOfAddToCartToPurchase}]", args: 1, defaultValue: defaultPercentageOfAddToCartToPurchase)
cli.perrate(longOpt: 'percentageOfPurchasesToRate', "The percentage of purchases to rate [defaults to ${defaultPercentageOfPurchasesToRate}]", args: 1, defaultValue: defaultPercentageOfPurchasesToRate)
cli.maxatct(longOpt: 'maxRandomTimeFromViewToAddToCartInMinutes', "The max random time from view to add-to-cart for a given user and product [defaults to ${defaultMaxRandomTimeFromViewToAddToCartInMinutes}]", args: 1, defaultValue: defaultMaxRandomTimeFromViewToAddToCartInMinutes)
cli.maxpurt(longOpt: 'maxRandomTimeFromAddToCartToPurchased', "The max random time from add-to-cart to purchased for a given user and product[defaults to ${defaultMaxRandomTimeFromAddToCartToPurchased}]", args: 1, defaultValue: defaultMaxRandomTimeFromAddToCartToPurchased)
cli.maxpd(longOpt: 'maxPastDate', "The max date in the past for the memberSince field of a given user [defaults to ${defaultMaxPastDate}]", args: 1, defaultValue: defaultMaxPastDate)
cli.numpeeps(longOpt: 'numberOfPeopleToCreate', "The number of people to create [defaults to ${defaultNumberOfPeopleToCreate}]", args: 1, defaultValue: defaultNumberOfPeopleToCreate)
cli.numprods(longOpt: 'numberOfProductsToCreate', "The number of products to create [defaults to ${defaultNumberOfProductsToCreate}]", args: 1, defaultValue: defaultNumberOfProductsToCreate)
cli.ncbs(longOpt: 'nodeCreationBatchSize', "The batch size to use for writes when creating people and products [defaults to ${defaultNodeCreationBatchSize}]", args: 1, defaultValue: defaultNodeCreationBatchSize)
cli.maxtax(longOpt: 'maxTaxRate', "The max tax rate for an order [defaults to ${defaultMaxTaxRate}]", args: 1, defaultValue: defaultMaxTaxRate)
cli.mintax(longOpt: 'minTaxRate', "The min tax rate for an order [defaults to ${defaultMinTaxRate}]", args: 1, defaultValue: defaultMinTaxRate)
cli.maxship(longOpt: 'maxShipRate', "The max ship rate for an order [defaults to ${defaultMaxShipRate}]", args: 1, defaultValue: defaultMaxShipRate)
cli.minship(longOpt: 'minShipRate', "The min ship rate for an order [defaults to ${defaultMinShipRate}]", args: 1, defaultValue: defaultMinShipRate)
cli.maxppp(longOpt: 'maxPerProductPrice', "The max price per product [defaults to ${defaultMaxPerProductPrice}]", args: 1, defaultValue: defaultMaxPerProductPrice)
cli.minppp(longOpt: 'minPerProductPrice', "The min price per product [defaults to ${defaultMinPerProductPrice}]", args: 1, defaultValue: defaultMinPerProductPrice)
cli.tc(longOpt: 'threadCount', "The thread count to use [defaults to ${defaultThreadCount}]", args: 1, defaultValue: defaultThreadCount)
cli.nuri(longOpt: 'neo4jUri', "The connect string, URI, to use for connecting to neo4j. [defaults to ${defaultNeo4jUri}]", args: 1, defaultValue: defaultNeo4jUri)
cli.nu(longOpt: 'neo4jUsername', "The neo4j username to use for authentication. [defaults to ${defaultNeo4jUsername}]", args: 1, defaultValue: defaultNeo4jUsername)
cli.np(longOpt: 'neo4jPassword', "The neo4j password to use for authentication. [defaults to ${defaultNeo4jPassword}]", args: 1, defaultValue: defaultNeo4jPassword)
cli.h(longOpt: 'help', 'Usage Information')

// parse and validate options
def cliOptions = cli.parse(args)

if (!cliOptions) {
  cli.usage()
  System.exit(-1)
}

if (cliOptions.help) {
  cli.usage()
  System.exit(0)
}

def printErr = System.err.&println

// closure for validating min/max are discrete
def validateParams = { variable, min, max ->
  if (min == max) {
    printErr("Min and max values must be discrete. Current for ${variable} are ${min} and ${max}")
    cli.usage()
    System.exit(-1)
  }
}

def maxPotentialViews = cliOptions.maxPotentialViews as Integer
def minPotentialViews = cliOptions.minPotentialViews as Integer
validateParams('Potential Views', minPotentialViews, maxPotentialViews)

def percentageOfViewsToAddToCart = cliOptions.percentageOfViewsToAddToCart as Integer
def percentageOfAddToCartToPurchase = cliOptions.percentageOfAddToCartToPurchase as Integer
def percentageOfPurchasesToRate = cliOptions.percentageOfAddToCartToPurchase as Integer
def maxRandomTimeFromViewToAddToCartInMinutes = cliOptions.maxRandomTimeFromViewToAddToCartInMinutes as Integer

def maxPastDate = cliOptions.maxPastDate as Integer
def numberOfPeopleToCreate = cliOptions.numberOfPeopleToCreate as Integer
def numberOfProductsToCreate = cliOptions.numberOfProductsToCreate as Integer

def nodeCreationBatchSize = cliOptions.nodeCreationBatchSize as Integer
def maxTaxRate = cliOptions.maxTaxRate as Double
def minTaxRate = cliOptions.minTaxRate as Double
validateParams('Tax Rate', minTaxRate, maxTaxRate)

def maxShipRate = cliOptions.maxShipRate as Double
def minShipRate = cliOptions.minShipRate as Double
validateParams('Ship Rate', minShipRate, maxShipRate)

def maxPerProductPrice = cliOptions.maxPerProductPrice as Double
def minPerProductPrice = cliOptions.minPerProductPrice as Double
validateParams('Product Price', minPerProductPrice, maxPerProductPrice)

// this is used for consistent label/type keys through the generation script
@Singleton
class GraphKeys {
  def personNodeType = 'person'
  def productNodeType = 'product'
  def orderNodeType = 'order'
  def viewEdgeType = 'view'
  def addToCartEdgeType = 'addtocart'
  def transactEdgeType = 'transact'
  def containEdgeType = 'contain'
  def ratingEdgeType = 'rating'
}

def globalOrderIdCounter = 0
def mathContext = new MathContext(2, RoundingMode.HALF_UP)

@Canonical class Person {

  def id
  def name
  def address
  def age
  def memberSince

  def toCypherCreate() {
    "(:${GraphKeys.instance.personNodeType} {id: ${id}, name:\"${name}\",age:${age},address:\"${address}\",memberSince:${memberSince.toEpochSecond(ZoneOffset.UTC)}})"
  }

  def toCypherId() {
    "(p${id})"
  }

  def toCypherMatch() {
    "(p${id}:${GraphKeys.instance.personNodeType} {id: ${id}})"
  }
}

@Canonical class Product {

  def id
  def name
  def manufacturer
  def msrp

  def toCypherCreate() {
    "(:${GraphKeys.instance.productNodeType} {id: ${id},name:\"${name}\",manufacturer:\"${manufacturer}\",msrp:'${msrp}'})"
  }

  def toCypherId() {
    "(prd${id})"
  }

  def toCypherMatch() {
    "(prd${id}:${GraphKeys.instance.productNodeType} {id: ${id}})"
  }
}

@Canonical class Order {

  def id
  def subTotal
  def tax
  def shipping
  def total
  def products
  def transactionTime

  def toCypherCreate() {
    "(o${id}:${GraphKeys.instance.orderNodeType} {id: ${id},subTotal:${subTotal},tax:${tax},shipping:${shipping},total:${total}})"
  }

  def toCypherId() {
    "(o${id})"
  }

  def toCypherMatch() {
    "(o${id}:${GraphKeys.instance.orderNodeType} {id: ${id}})"
  }
}

@Canonical class Rating extends TemporalEdge {

  protected rating

  def toCypherCreate() {
    "${source.toCypherId()}-[:${type} {time: ${time.toEpochSecond(ZoneOffset.UTC)}, rating: ${rating}}]->${destination.toCypherId()}"
  }
}

@Canonical class TemporalEdge extends Edge {

  protected time

  def toCypherCreate() {
    "${source.toCypherId()}-[:${type} {time: ${time.toEpochSecond(ZoneOffset.UTC)}}]->${destination.toCypherId()}"
  }
}

@Canonical class Edge {

  protected source
  protected destination
  protected type

  def toCypherCreate() {
    "${source.toCypherId()}-[:${type}]->${destination.toCypherId()}"
  }
}

@Canonical
@Sortable
class Query {

  static Integer retryCount = 3

  Boolean containsMatch
  String statement
  Integer executionAttemptCount = 0

  String getAndCount() {
    executionAttemptCount++
    statement
  }

  Boolean retry() {
    executionAttemptCount <= retryCount
  }
}

def random = new SplittableRandom()
def mainThreadFaker = new Faker()

// setup jedis and graph
def threadCount = cliOptions.threadCount as Integer

def now = LocalDateTime.now()

def maxQueueLengthExpected = numberOfPeopleToCreate * 2 + numberOfProductsToCreate
def queue = new PriorityBlockingQueue(maxQueueLengthExpected, Query.comparatorByContainsMatch())
def terminationLatch = new CountDownLatch(threadCount)
def generationComplete = new AtomicBoolean(false)
def neo4jdb = GraphDatabase.driver(cliOptions.neo4jUri, AuthTokens.basic(cliOptions.neo4jUsername, cliOptions.neo4jPassword))

Thread.start {
  new ProgressBar('Creating...', maxQueueLengthExpected, progressBarUpdateInterval).withCloseable { progressBar ->
    threadCount.times {
      Thread.start {
        while (terminationLatch.count) {
          def query = queue.poll(1, TimeUnit.SECONDS)

          if (query && !query.containsMatch) {

            def batchCounter = 0
            def batchCreateQueries = []

            while (query && !query.containsMatch && batchCounter < nodeCreationBatchSize) {
              batchCreateQueries << query
              batchCounter++
              query = queue.poll(1, TimeUnit.SECONDS)
            }

            if (query.containsMatch) {
              queue.offer(query)
            }

            if (batchCreateQueries) {

              try {
                neo4jdb.session().withCloseable { session ->
                  session.run("CREATE ${batchCreateQueries.collect { it.getAndCount() }.join(',')}")
                }
                progressBar.stepBy(batchCreateQueries.size() as Long)
              } catch (Exception e) {
                progressBar.stepBy(progressBar.getCurrent() - batchCreateQueries.size() as Long)
                batchCreateQueries.each { localQuery ->
                  if (localQuery.retry()) {
                    queue.offer(localQuery)
                  } else {
                    printErr("The following query failed execution ${Query.retryCount} times: CREATE ${batchCreateQueries.collect { it.statement }.join(',')}")
                  }
                }
              }
            }
          } else if (query) {
            try {
              neo4jdb.session().withCloseable { session ->
                session.run(query.getAndCount())
              }
              progressBar.step()
            } catch (Exception e) {
              progressBar.stepBy(progressBar.getCurrent() - 1)
              if (query.retry()) {
                queue.offer(query)
              } else {
                printErr("The following query failed execution ${Query.retryCount} times: ${query.statement}")
              }
            }
          } else if (generationComplete.get()) {
            terminationLatch.countDown()
          }
        }
      }
    }

    terminationLatch.await()
  }
}

// generate the people and products, here is where the prior threads begin doing their work
def people = new ArrayList(numberOfPeopleToCreate)

numberOfPeopleToCreate.times { num ->
  def address = mainThreadFaker.address()
  def person = new Person(id: num, name: "${address.firstName()} ${address.lastName()}", address: address.fullAddress(), age: random.nextInt(10, 100), memberSince: LocalDateTime.now().minusDays(random.nextInt(1, maxPastDate) as Long))
  queue.offer(new Query(containsMatch: false, statement: person.toCypherCreate()), 10, TimeUnit.SECONDS)
  people << person
}

def products = new ArrayList(numberOfProductsToCreate)

numberOfProductsToCreate.times { num ->
  def product = new Product(id: num, name: mainThreadFaker.commerce().productName(), manufacturer: mainThreadFaker.company().name(), msrp: mainThreadFaker.commerce().price(minPerProductPrice, maxPerProductPrice) as Double)
  queue.offer(new Query(containsMatch: false, statement: product.toCypherCreate()), 10, TimeUnit.SECONDS)
  products << product
}

people.each { person ->

  def views = random.nextInt(minPotentialViews, maxPotentialViews)
  def viewedProducts = [] as Set
  def minutesFromMemberSinceToNow = person.memberSince.until(now, ChronoUnit.MINUTES)

  // generate random value up to max potential views and drop those into a unique set
  views.times {
    viewedProducts << products.get(random.nextInt(products.size()))
  }

  def viewEdges = viewedProducts.collect { product ->
    new TemporalEdge(source: person, destination: product, type: GraphKeys.instance.viewEdgeType, time: person.memberSince.plusMinutes(random.nextInt(1, minutesFromMemberSinceToNow as Integer) as Long))
  }

  if (viewEdges) {

    // iterate through view edges and give each a random opportunity to become an add to cart edge
    def addedToCartEdges = viewEdges.findAll {
      random.nextInt(100) <= percentageOfViewsToAddToCart
    }.collect { edge ->
      new TemporalEdge(source: edge.source, destination: edge.destination, type: GraphKeys.instance.addToCartEdgeType, time: edge.time.plusMinutes(random.nextInt(1, maxRandomTimeFromViewToAddToCartInMinutes) as Long))
    }

    // add to cart events that should go on to be purchases, though they are still add to cart events
    def addToCartEventsToPurchase = addedToCartEdges.findAll {
      random.nextInt(100) <= percentageOfAddToCartToPurchase
    }

    def orders = []

    if (addToCartEventsToPurchase) {

      addToCartEventsToPurchase.collate(random.nextInt(0, addToCartEventsToPurchase.size())).collect { subSetOfAddToCartEventsToPurchase ->

        def productsInOrder = subSetOfAddToCartEventsToPurchase.collect { event -> event.destination }
        def subTotalOfProducts = BigDecimal.valueOf(productsInOrder.collect { product -> product.msrp }.sum()).setScale(2, BigDecimal.ROUND_HALF_UP)
        def taxAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(random.nextDouble(minTaxRate, maxTaxRate), mathContext))
        def shipAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(random.nextDouble(minShipRate, maxShipRate), mathContext))
        def maxAddToCartTime = subSetOfAddToCartEventsToPurchase.collect { event -> event.time }.max()

        def total = subTotalOfProducts.add(taxAddition).add(shipAddition).setScale(2, BigDecimal.ROUND_HALF_UP)

        orders << new Order(id: globalOrderIdCounter++, transactionTime: maxAddToCartTime, products: productsInOrder, subTotal: subTotalOfProducts, tax: taxAddition, shipping: shipAddition, total: total)
      }
    }

    def matchStatement = "MATCH ${person.toCypherMatch()}"

    // views are the basis for all other events, i.e. add to cart, purchase, etc...
    def matchProducts = [] as Set
    viewEdges.each { event ->
      matchProducts << event.destination
    }
    matchProducts.each { product ->
      matchStatement += ", ${product.toCypherMatch()}"
    }

    if (viewEdges) {

      matchStatement += ' CREATE '
      matchStatement += viewEdges.collect { event -> event.toCypherCreate() }.join(', ')

      if (addedToCartEdges) {
        matchStatement += ', '
        matchStatement += addedToCartEdges.collect { event -> event.toCypherCreate() }.unique().join(', ')

        if (orders) {

          matchStatement += ', '
          matchStatement += orders.collect { order -> order.toCypherCreate() }.join(', ')

          def orderEdges = []

          orders.each { order ->
            orderEdges << new TemporalEdge(source: person, destination: order, type: GraphKeys.instance.transactEdgeType, time: order.transactionTime)
            order.products.each { product->
              orderEdges << new Edge(source: order, destination: product, type: GraphKeys.instance.containEdgeType)
              if (random.nextInt(100) <= percentageOfPurchasesToRate) {
                orderEdges << new Rating(source: person, destination: product, type: GraphKeys.instance.ratingEdgeType, time: order.transactionTime, rating: random.nextInt(10))
              }
            }
          }

          matchStatement += ', '
          matchStatement += orderEdges.collect { edge -> edge.toCypherCreate() }.join(', ')
        }
      }
    }

    queue.offer(new Query(containsMatch: true, statement: matchStatement))
  }
}

generationComplete.set(true)
terminationLatch.await()
