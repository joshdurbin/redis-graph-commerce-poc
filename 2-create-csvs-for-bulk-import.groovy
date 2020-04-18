@Grapes([
  @Grab(group='com.github.javafaker', module='javafaker', version='1.0.1'),
  @Grab(group='org.apache.commons', module='commons-lang3', version='3.9'),
])

import groovy.transform.Canonical

import java.util.concurrent.TimeUnit
import java.util.SplittableRandom
import java.time.temporal.ChronoUnit
import java.time.LocalDateTime
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

import com.github.javafaker.Faker
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
def maxTaxRate =  0.125
def minTaxRate = 0.0
def maxShipRate = 0.15
def minShipRate = 0.0
def minPerProductPrice = 0.99
def maxPerProductPrice = 1000.00

def mathContext = new MathContext(2, RoundingMode.HALF_UP)

def encoding = 'utf-8'

@Canonical class Person {

  def bulkLoaderInternalId
  def id
  def name
  def address
  def age
  def memberSince
}

@Canonical class Product {

  def bulkLoaderInternalId
  def id
  def name
  def manufacturer
  def msrp
}

@Canonical class Event {

  def product
  def time
}

@Canonical class Order {

  def bulkLoaderInternalId
  def id
  def subTotal
  def tax
  def shipping
  def total
}

def random = new SplittableRandom()
def faker = new Faker()

def now = LocalDateTime.now()

def redisBulkLoadInternalNodeCounter = 0

def people = []

// creates people objects
new File('person.csv').withWriter(encoding) { writer ->

  // the first position is the internal loader id which is prefixed with an underscore so it's not in the resulting graph
  writer.writeLine('_internalid,id,name,address,age,memberSince')
  random.nextInt(minPotentialPeopleToCreate, maxPotentialPeopleToCreate).times { num ->
    def address = faker.address()
    def person = new Person(bulkLoaderInternalId: redisBulkLoadInternalNodeCounter++, id: num, name: "${address.firstName()} ${address.lastName()}", address: address.fullAddress(), age: random.nextInt(10, 100), memberSince: LocalDateTime.now().minusDays(random.nextInt(1, maxPastDate) as Long))

    people << person
    writer.writeLine("${person.bulkLoaderInternalId},${person.id},${person.name},${person.address.replaceAll(',','')},${person.age},${person.memberSince}")
  }
}

def products = []

// create products
new File('product.csv').withWriter(encoding) { writer ->
  // the first position is the internal loader id which is prefixed with an underscore so it's not in the resulting graph
  writer.writeLine('_internalid,id,name,manufacturer,msrp')
  random.nextInt(minPotentialProductsToCreate, maxPotentialProductsToCreate).times { num ->
    def product = new Product(bulkLoaderInternalId: redisBulkLoadInternalNodeCounter++, id: num, name: faker.commerce().productName(), manufacturer: faker.company().name(), msrp: faker.commerce().price(0.99, 1000.00) as Double)

    products << product
    writer.writeLine("${product.bulkLoaderInternalId},${product.id},${product.name.replaceAll(',','')},${product.manufacturer.replaceAll(',','')},${product.msrp}")
  }
}

def orderCounter = 0

new File('view.csv').withWriter(encoding) { viewEdgeWriter ->
  viewEdgeWriter.writeLine('src_person,dst_product,timestamp')

  new File('addtocart.csv').withWriter(encoding) { addToCartEdgeWriter ->
    addToCartEdgeWriter.writeLine('src_person,dst_product,timestamp')

    new File('transact.csv').withWriter(encoding) { transactEdgeWriter ->
      transactEdgeWriter.writeLine('src_person,dst_order')

      new File('order.csv').withWriter(encoding) { orderNodeWriter ->
        // the first position is the internal loader id which is prefixed with an underscore so it's not in the resulting graph
        orderNodeWriter.writeLine('_internalid,id,subTotal,tax,shipping,total')

        new File('contain.csv').withWriter(encoding) { containEdgeWriter ->
          containEdgeWriter.writeLine('src_person,dst_order')

          people.each { person ->

            def views = random.nextInt(minPotentialViews, maxPotentialViews)
            def viewedProducts = [] as Set
            def minutesFromMemberSinceToNow = person.memberSince.until(now, ChronoUnit.MINUTES)

            // generate random value up to max potential views and drop those into a unique set
            views.times {
              viewedProducts << products.get(random.nextInt(products.size()))
            }

            def viewEvents = viewedProducts.collect { product ->
              def event = new Event(product: product, time: person.memberSince.plusMinutes(random.nextInt(1, minutesFromMemberSinceToNow as Integer) as Long))
              viewEdgeWriter.writeLine("${person.bulkLoaderInternalId},${product.bulkLoaderInternalId},${event.time}")

              event
            }

            def addedToCartEvents = viewEvents.findAll {
              random.nextInt(100) <= percentageOfViewsToAddToCart
            }.collect { event ->
              def addToCartEvent = new Event(product: event.product, time: event.time.plusMinutes(random.nextInt(1, maxRandomTimeFromViewToAddToCartInMinutes) as Long))
              addToCartEdgeWriter.writeLine("${person.bulkLoaderInternalId},${event.product.bulkLoaderInternalId},${addToCartEvent.time}")

              addToCartEvent
            }

            def purchasedEvents = addedToCartEvents.findAll {
              random.nextInt(100) <= percentageOfAddToCartToPurchase
            }

            if (purchasedEvents) {

              purchasedEvents.collate(random.nextInt(0, purchasedEvents.size())).collect { subSetOfPurchasedEvents ->

                def productsInOrder = subSetOfPurchasedEvents.collect { event -> event.product }
                def oldestPurchasedEvent = subSetOfPurchasedEvents.max { event -> event.time }
                def subTotalOfProducts = productsInOrder.collect { product -> product.msrp }.sum()
                def taxAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(random.nextDouble(minTaxRate, maxTaxRate), mathContext))
                def shipAddition = new BigDecimal(subTotalOfProducts, mathContext).multiply(new BigDecimal(random.nextDouble(minShipRate, maxShipRate), mathContext))

                def order = new Order(bulkLoaderInternalId: redisBulkLoadInternalNodeCounter++, id: orderCounter++, subTotal: subTotalOfProducts, tax: taxAddition, shipping: shipAddition, total: subTotalOfProducts + taxAddition + shipAddition)

                transactEdgeWriter.writeLine("${person.bulkLoaderInternalId},${order.bulkLoaderInternalId}")
                orderNodeWriter.writeLine("${order.bulkLoaderInternalId},${order.id},${order.subTotal},${order.tax},${order.shipping},${order.total}")

                productsInOrder.each { product ->
                  containEdgeWriter.writeLine("${order.bulkLoaderInternalId},${product.bulkLoaderInternalId}")
                }
              }
            }
          }
        }
      }
    }
  }
}
