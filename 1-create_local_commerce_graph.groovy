@Grapes([
  @Grab(group='com.redislabs', module='jredisgraph', version='2.0.0-rc3'),
  @Grab(group='com.github.javafaker', module='javafaker', version='1.0.1'),
  @Grab(group='org.apache.commons', module='commons-lang3', version='3.9'),
  @Grab(group='com.google.guava', module='guava', version='28.1-jre')
])

import groovy.lang.Singleton
import groovy.transform.Canonical

import java.util.concurrent.TimeUnit
import java.util.SplittableRandom
import java.time.temporal.ChronoUnit
import java.time.LocalDateTime
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

import com.github.javafaker.Faker
import com.google.common.base.Stopwatch
import com.redislabs.redisgraph.impl.api.RedisGraph
import org.apache.commons.lang3.RandomStringUtils

def maxPotentialViews = 50
def minPotentialViews = 0
def percentageOfViewsToAddToCart = 25
def percentageOfAddToCartToPurchase = 15
def maxRandomTimeFromViewToAddToCartInMinutes = 4320
def maxRandomTimeFromAddToCartToPurchased = 4320
def maxPastDate = 365 * 20
def maxPotentialPeopleToCreate = 1_001
def minPotentialPeopleToCreate = 1_000
def maxPotentialProductsToCreate = 1_001
def minPotentialProductsToCreate = 1_000
def nodeCreationBatchSize = 500
def maxTaxRate =  0.125
def minTaxRate = 0.0
def maxShipRate = 0.15
def minShipRate = 0.0
def minPerProductPrice = 0.99
def maxPerProductPrice = 1000.00

def viewsEdgeCount = 0
def addToCartEdgeCount = 0
def placedEdgeCount = 0
def containsEdgeCount = 0

@Singleton class GraphKeys {
  def personNodeType = 'person'
  def productNodeType = 'product'
  def orderNodeType = 'order'
  def viewEdgeType = 'view'
  def addToCartEdgeType = 'addtocart'
  def transactEdgeType = 'transact'
  def containEdgeType = 'contain'
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

def totalTimer = Stopwatch.createStarted()

def graph = new RedisGraph()
def random = new SplittableRandom()
def faker = new Faker()

def db = 'prodrec'

def now = LocalDateTime.now()

def people = []

// creates people objects
random.nextInt(minPotentialPeopleToCreate, maxPotentialPeopleToCreate).times { num ->
  def address = faker.address()
  people << new Person(id: num, name: "${address.firstName()} ${address.lastName()}", address: address.fullAddress(), age: random.nextInt(10, 100), memberSince: LocalDateTime.now().minusDays(random.nextInt(1, maxPastDate) as Long))
}

// insert nodes
def timer = Stopwatch.createStarted()
def batchedPeople = people.collate(nodeCreationBatchSize)
batchedPeople.each { batch ->
  graph.query(db, "CREATE ${batch.collect { person -> person.toCypherCreate()}.join(',')}")
}
println "Finished creating ${people.size()} people nodes in ${timer.elapsed(TimeUnit.MILLISECONDS)} ms"
timer.reset()

def products = []

// create products
random.nextInt(minPotentialProductsToCreate, maxPotentialProductsToCreate).times { num ->
  products << new Product(id: num, name: faker.commerce().productName(), manufacturer: faker.company().name(), msrp: faker.commerce().price(minPerProductPrice, maxPerProductPrice) as Double)
}

// insert product nodes
timer.start()
def batchedProducts = products.collate(nodeCreationBatchSize)
batchedProducts.each { batch ->
  graph.query(db, "CREATE ${batch.collect { person -> person.toCypherCreate()}.join(',')}")
}
println "Finished creating ${products.size()} products nodes in ${timer.elapsed(TimeUnit.MILLISECONDS)} ms"
timer.reset()

// iterate over people to create edges for each person. this is not done in batch spanning people
people.each { person ->

  def views = random.nextInt(minPotentialViews, maxPotentialViews)
  def viewedProducts = [] as Set
  def minutesFromMemberSinceToNow = person.memberSince.until(now, ChronoUnit.MINUTES)

  // generate random value up to max potential views and drop those into a unique set
  views.times {
    viewedProducts << products.get(random.nextInt(products.size()))
  }

  def viewEvents = viewedProducts.collect { product ->
    new Event(product: product, type: GraphKeys.instance.viewEdgeType, time: person.memberSince.plusMinutes(random.nextInt(1, minutesFromMemberSinceToNow as Integer) as Long))
  }

  viewsEdgeCount += viewedProducts.size()

  if (viewEvents) {

    // this pulls a percentage of viewed products into add to cart events
    def addedToCartEvents = viewEvents.findAll {
      random.nextInt(100) <= percentageOfViewsToAddToCart
    }.collect { event ->
      new Event(product: event.product, type: GraphKeys.instance.addToCartEdgeType, time: event.time.plusMinutes(random.nextInt(1, maxRandomTimeFromViewToAddToCartInMinutes) as Long))
    }

    // purchasedEvents
    def purchasedEvents = addedToCartEvents.findAll {
      random.nextInt(100) <= percentageOfAddToCartToPurchase
    }

    def addedToCartEventEdges = ''

    if (addedToCartEvents) {

      def joinedAddToCartEdges = addedToCartEvents.collect { event ->
        "(p)-[:${event.type} {time: '${event.time}'}]->(prd${event.product.id})"
      }.join(', ')
      addedToCartEventEdges = ", ${joinedAddToCartEdges}"
      addToCartEdgeCount += addedToCartEvents.size()

      if (purchasedEvents) {

        purchasedEvents.collate(random.nextInt(0, purchasedEvents.size())).collect { subSetOfPurchasedEvents ->

          def productsInOrder = subSetOfPurchasedEvents.collect { event -> event.product }
          def oldestPurchasedEvent = subSetOfPurchasedEvents.max { event -> event.time }
          def subTotalOfProducts = productsInOrder.collect { product -> product.msrp }.sum()
          def taxAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(random.nextDouble(minTaxRate, maxTaxRate), mathContext))
          def shipAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(random.nextDouble(minShipRate, maxShipRate), mathContext))

          def order = new Order(id: globalOrderIdCounter++, subTotal: subTotalOfProducts, tax: taxAddition, shipping: shipAddition, total: subTotalOfProducts + taxAddition + shipAddition)

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

          containsEdgeCount += productsInOrder.size()
          placedEdgeCount++

          def thing = "MATCH (p:${GraphKeys.instance.personNodeType}), (o:${GraphKeys.instance.orderNodeType}), ${productMatchDefinitionParams} WHERE p.id=${person.id} AND o.id=${order.id} AND ${productMatchCriteria} CREATE (p)-[:${GraphKeys.instance.transactEdgeType}]->(o), ${productEdges}"

          graph.query(db, thing)
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

    timer.start()
    graph.query(db, query)
    println "Created edges for person id ${person.id} with ${viewEvents.size()} product views and ${addedToCartEvents.size()} products added to cart and ${purchasedEvents.size()} products purchased in ${timer.elapsed(TimeUnit.MILLISECONDS)} ms..."
    timer.reset()
  }
}

println "Finished creating ${people.size()} '${GraphKeys.instance.personNodeType}', ${products.size()} '${GraphKeys.instance.productNodeType}', ${globalOrderIdCounter} '${GraphKeys.instance.orderNodeType}', ${viewsEdgeCount} '${GraphKeys.instance.viewEdgeType}' edges, ${addToCartEdgeCount} '${GraphKeys.instance.addToCartEdgeType}' edges, ${placedEdgeCount} '${GraphKeys.instance.transactEdgeType}' edges, and ${containsEdgeCount} '${GraphKeys.instance.containEdgeType}' products edges in ${totalTimer.elapsed(TimeUnit.MILLISECONDS)} ms..."
