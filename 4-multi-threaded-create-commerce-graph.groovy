@Grapes([
  @Grab(group='com.redislabs', module='jredisgraph', version='2.0.0'),
  @Grab(group='com.github.javafaker', module='javafaker', version='1.0.1'),
  @Grab(group='org.apache.commons', module='commons-lang3', version='3.9'),
  @Grab(group='org.apache.commons', module='commons-pool2', version='2.8.0'),
  @Grab(group='redis.clients', module='jedis', version='3.2.0'),
  @Grab(group='com.google.guava', module='guava', version='28.1-jre'),
  @Grab(group='com.github.oshi', module='oshi-core', version='4.6.1'),
  @Grab(group='me.tongfei', module='progressbar', version='0.7.3'),
  @Grab(group='org.slf4j', module='slf4j-simple', version='1.7.30'),
])

import groovy.lang.Singleton
import groovy.transform.Canonical
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.SplittableRandom
import java.time.temporal.ChronoUnit
import java.time.LocalDateTime
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import redis.clients.jedis.JedisPool
import me.tongfei.progressbar.ProgressBar
import com.github.javafaker.Faker
import com.google.common.base.Stopwatch
import com.redislabs.redisgraph.impl.api.RedisGraph
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.pool2.impl.GenericObjectPoolConfig

def maxPotentialViews = 50
def minPotentialViews = 0
def percentageOfViewsToAddToCart = 25
def percentageOfAddToCartToPurchase = 90
def maxRandomTimeFromViewToAddToCartInMinutes = 4320
def maxRandomTimeFromAddToCartToPurchased = 4320
def maxPastDate = 365 * 20
def maxPotentialPeopleToCreate = 5_001
def minPotentialPeopleToCreate = 5_000
def maxPotentialProductsToCreate = 1_001
def minPotentialProductsToCreate = 1_000
def nodeCreationBatchSize = 500
def maxTaxRate =  0.125
def minTaxRate = 0.0
def maxShipRate = 0.15
def minShipRate = 0.0
def minPerProductPrice = 0.99
def maxPerProductPrice = 1000.00

@Singleton class GraphKeys {
  def personNodeType = 'person'
  def productNodeType = 'product'
  def orderNodeType = 'order'
  def viewEdgeType = 'view'
  def addToCartEdgeType = 'addtocart'
  def transactEdgeType = 'transact'
  def containEdgeType = 'contain'
}

def globalOrderIdCounter = new AtomicInteger(0)

def mathContext = new MathContext(2, RoundingMode.HALF_UP)

@Canonical class Person {

  def id
  def name
  def address
  def age
  def memberSince

  def toCypherCreate() {
    "(:${GraphKeys.instance.personNodeType} {id: ${id}, name:\"${name}\",age:${age},address:\"${address}\",memberSince:\"${memberSince}\"})"
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
}

@Canonical class Order {

  def id
  def subTotal
  def tax
  def shipping
  def total

  def toCypherCreate() {
    "(:${GraphKeys.instance.orderNodeType} {id: ${id},subTotal:${subTotal},tax:${tax},shipping:${shipping},total:${total}})"
  }
}

@Canonical class Event {

  def product
  def type
  def time
}

def random = new SplittableRandom()
def faker = new Faker()

def db = 'prodrec'

def threadCount = 6
def config = new GenericObjectPoolConfig()
config.setMaxTotal(threadCount)

def jedisPool = new JedisPool(config)
def graph = new RedisGraph(jedisPool)

graph.query(db, "create index on :person(id)")
graph.query(db, "create index on :product(id)")
graph.query(db, "create index on :order(id)")

def now = LocalDateTime.now()

def personAndProductBatchedInsertQueue = new ConcurrentLinkedQueue()

def personAndProductLatch = new CountDownLatch(threadCount)

def queue = new ConcurrentLinkedQueue()
def peopleToCreate = random.nextInt(minPotentialPeopleToCreate, maxPotentialPeopleToCreate)
def productsToCreate = random.nextInt(minPotentialProductsToCreate, maxPotentialProductsToCreate)
def generationDone = new AtomicBoolean(false)
def createCount = new AtomicInteger(0)

threadCount.times {

  Thread.start {

    while (personAndProductLatch.count > 0L) {

      def query = personAndProductBatchedInsertQueue.poll()
      if (query) {
        graph.query(db, query)
        createCount.getAndIncrement()
      } else if (generationDone.get()) {
        personAndProductLatch.countDown()
      }
    }
  }
}

Thread.start {

  def pb = new ProgressBar("creating (:${GraphKeys.instance.personNodeType}) and (:${GraphKeys.instance.productNodeType})", peopleToCreate + productsToCreate)

  while (createCount.get() != peopleToCreate + productsToCreate) {
    pb.stepTo(createCount.get())
  }

  pb.close()
}

def peopleQueueToCreateOrdersAndViewAddToCartAndTransactEdges = new ConcurrentLinkedQueue()
peopleToCreate.times { num ->
  def address = faker.address()
  def person = new Person(id: num, name: "${address.firstName()} ${address.lastName()}", address: address.fullAddress(), age: random.nextInt(10, 100), memberSince: LocalDateTime.now().minusDays(random.nextInt(1, maxPastDate) as Long))
  personAndProductBatchedInsertQueue.offer("CREATE ${person.toCypherCreate()}")
  peopleQueueToCreateOrdersAndViewAddToCartAndTransactEdges.offer(person)
}

def products = new ArrayList(productsToCreate)
productsToCreate.times { num ->
  def product = new Product(id: num, name: faker.commerce().productName(), manufacturer: faker.company().name(), msrp: faker.commerce().price(minPerProductPrice, maxPerProductPrice) as Double)
  products << product
  personAndProductBatchedInsertQueue.offer("CREATE ${product.toCypherCreate()}")
}

generationDone.set(true)
personAndProductLatch.await()

def edgeAndOrderGenerationLatch = new CountDownLatch(threadCount)

threadCount.times {

  Thread.start { thread ->

    def threadRandom = new SplittableRandom()

    while (!peopleQueueToCreateOrdersAndViewAddToCartAndTransactEdges.empty) {

      def person = peopleQueueToCreateOrdersAndViewAddToCartAndTransactEdges.poll()

      def views = threadRandom.nextInt(minPotentialViews, maxPotentialViews)
      def viewedProducts = [] as Set
      def minutesFromMemberSinceToNow = person.memberSince.until(now, ChronoUnit.MINUTES)

      // generate random value up to max potential views and drop those into a unique set
      views.times {
        viewedProducts << products.get(threadRandom.nextInt(products.size()))
      }

      def viewEvents = viewedProducts.collect { product ->
        new Event(product: product, type: GraphKeys.instance.viewEdgeType, time: person.memberSince.plusMinutes(threadRandom.nextInt(1, minutesFromMemberSinceToNow as Integer) as Long))
      }

      if (viewEvents) {

        // this pulls a percentage of viewed products into add to cart events
        def addedToCartEvents = viewEvents.findAll {
          threadRandom.nextInt(100) <= percentageOfViewsToAddToCart
        }.collect { event ->
          new Event(product: event.product, type: GraphKeys.instance.addToCartEdgeType, time: event.time.plusMinutes(threadRandom.nextInt(1, maxRandomTimeFromViewToAddToCartInMinutes) as Long))
        }

        // purchasedEvents
        def purchasedEvents = addedToCartEvents.findAll {
          threadRandom.nextInt(100) <= percentageOfAddToCartToPurchase
        }

        def addedToCartEventEdges = ''

        if (addedToCartEvents) {

          def joinedAddToCartEdges = addedToCartEvents.collect { event ->
            "(p)-[:${event.type} {time: '${event.time}'}]->(prd${event.product.id})"
          }.join(', ')
          addedToCartEventEdges = ", ${joinedAddToCartEdges}"

          if (purchasedEvents) {

            purchasedEvents.collate(threadRandom.nextInt(0, purchasedEvents.size())).collect { subSetOfPurchasedEvents ->

              def productsInOrder = subSetOfPurchasedEvents.collect { event -> event.product }
              def oldestPurchasedEvent = subSetOfPurchasedEvents.max { event -> event.time }
              def subTotalOfProducts = productsInOrder.collect { product -> product.msrp }.sum()
              def taxAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(threadRandom.nextDouble(minTaxRate, maxTaxRate), mathContext))
              def shipAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(threadRandom.nextDouble(minShipRate, maxShipRate), mathContext))

              def order = new Order(id: globalOrderIdCounter.incrementAndGet(), subTotal: subTotalOfProducts, tax: taxAddition, shipping: shipAddition, total: subTotalOfProducts + taxAddition + shipAddition)

              graph.query(db, "CREATE ${order.toCypherCreate()}")

              // we have to match across all the products
              def productMatchDefinitionParams = productsInOrder.collect { product ->
                "(prd${product.id}:${GraphKeys.instance.productNodeType})"
              }.join(', ')

              def productMatchCriteria = productsInOrder.collect { product ->
                "prd${product.id}.id=${product.id}"
              }.join(' AND ')

              def productEdges = productsInOrder.collect { product ->
                "(o)-[:contain]->(prd${product.id})"
              }.join(', ')

              def query = "MATCH (p:${GraphKeys.instance.personNodeType}), (o:${GraphKeys.instance.orderNodeType}), ${productMatchDefinitionParams} WHERE p.id=${person.id} AND o.id=${order.id} AND ${productMatchCriteria} CREATE (p)-[:${GraphKeys.instance.transactEdgeType}]->(o), ${productEdges}"

              graph.query(db, query)
            }
          }
        }

        // we have to match across all the products
        def productMatchDefinitionParams = viewEvents.collect { event ->
          "(prd${event.product.id}:${GraphKeys.instance.productNodeType})"
        }.join(', ')

        def productMatchCriteria = viewEvents.collect { event ->
          "prd${event.product.id}.id=${event.product.id}"
        }.join(' AND ')

        def viewedEdges = viewEvents.collect { event ->
          "(p)-[:${event.type} {time: '${event.time}'}]->(prd${event.product.id})"
        }.join(', ')

        def query = "MATCH (p:${GraphKeys.instance.personNodeType}), ${productMatchDefinitionParams} WHERE p.id=${person.id} AND ${productMatchCriteria} CREATE ${viewedEdges}${addedToCartEventEdges}"

        graph.query(db, query)
      }
    }

    edgeAndOrderGenerationLatch.countDown()
  }
}

def pb = new ProgressBar("creating (:${GraphKeys.instance.orderNodeType}), [:${GraphKeys.instance.viewEdgeType}], [:${GraphKeys.instance.addToCartEdgeType}], [:${GraphKeys.instance.transactEdgeType}], and [:${GraphKeys.instance.containEdgeType}]", peopleToCreate)

while (edgeAndOrderGenerationLatch.count > 0L) {
  pb.stepTo(peopleToCreate - peopleQueueToCreateOrdersAndViewAddToCartAndTransactEdges.size())
}

pb.close()
