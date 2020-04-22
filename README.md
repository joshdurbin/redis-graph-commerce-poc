# redis-graph-commerce-poc
Proof of concept work to generate a realistic commerce graph using [RedisGraph](http://redisgraph.io) and [OpenCypher](http://opencypher.org).

To leverage; run a local instance of redis-graph or use the docker image:

  - `docker pull redislabs/redisgraph`
  - `docker run -p 6379:6379 -it --rm -v redis-data:/data redislabs/redisgraph:latest`

### Generate the graph

Run the groovy script in this directory. It requires, ideally, Java 11, but 8 will work and Groovy. Groovy 3 is recommended, and uh, might actually be required -- I can't remember. The grape directives pull down Maven artifacts which is how we're able to pour so much logic into a script like this.

Running `./generateCommerceGraph -h` will generate the help menu for the generation script which, at the time of this writing is:

```
usage: generateCommerceGraph
Commerce Graph Generator
 -db,--database <arg>                                         The RedisGraph database to use for our queries, data generation [defaults to prodrec]
 -h,--help                                                    Usage Information
 -maxatct,--maxRandomTimeFromViewToAddToCartInMinutes <arg>   The max random time from view to add-to-cart for a given user and product [defaults to 4320]
 -maxpd,--maxPastDate <arg>                                   The max date in the past for the memberSince field of a given user [defaults to 7300]
 -maxpeeps,--maxPotentialPeopleToCreate <arg>                 The max number of people to create [defaults to 5001]
 -maxppp,--maxPerProductPrice <arg>                           The max price per product [defaults to 1000.00]
 -maxprods,--maxPotentialProductsToCreate <arg>               The max number of products to create [defaults to 1001]
 -maxpurt,--maxRandomTimeFromAddToCartToPurchased <arg>       The max random time from add-to-cart to purchased for a given user and product[defaults to 4320]
 -maxship,--maxShipRate <arg>                                 The max ship rate for an order [defaults to 0.15]
 -maxtax,--maxTaxRate <arg>                                   The max tax rate for an order [defaults to 0.125]
 -maxv,--maxPotentialViews <arg>                              The max number of potential views a person can have against a product [defaults to 50]
 -minpeeps,--minPotentialPeopleToCreate <arg>                 The min number of people to create [defaults to 5000]
 -minppp,--minPerProductPrice <arg>                           The min price per product [defaults to 0.99]
 -minprods,--minPotentialProductsToCreate <arg>               The min number of products to create [defaults to 1000]
 -minship,--minShipRate <arg>                                 The min ship rate for an order [defaults to 0.0]
 -mintax,--minTaxRate <arg>                                   The min tax rate for an order [defaults to 0.0]
 -minv,--minPotentialViews <arg>                              The min number of potential views a person can have against a product [defaults to 0]
 -ncbs,--nodeCreationBatchSize <arg>                          The batch size to use for writes when creating people and products [defaults to 500]
 -peratc,--percentageOfViewsToAddToCart <arg>                 The percentage of views to turn into add-to-cart events for a given person and product [defaults to 25]
 -perpur,--percentageOfAddToCartToPurchase <arg>              The percentage of add-to-cart into purchase events for a given person and product [defaults to 90]
 -rh,--redisHost <arg>                                        The host of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to null]
 -rp,--redisPort <arg>                                        The port of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to null]
 -tc,--threadCount <arg>                                      The thread count to use [defaults to 6]
```

Though there are tons of flags, you can simply run the script with defaults via: `./generateCommerceGraph`.

The generation script creates a random number of (1) people and (2) products bounded by the min/max for each. By default they're set to essentially 5k people and 1k products by defaulting the min/max such that those quantities are created.

The creation of people and products is done concurrently with batching for speed. Once all of this is done, which is shown with a progress bar, a second thread pool is created of the same size and we iterate over the people. This is where the other values come into play. The script will randomly generate up to a certain percentage outcomes for (1) view edges between people and products, (2) a sub-set of the view edges become add-to-cart edges, also between people and products, and (3) a sub-set of the add-to-cart edges become transact edges between people and orders. Orders have contain edges between themselves and products.

Progress of these generation scripts is additionally output.

### Querying the graph

Write queries against the graph using Redis Insight:

  - `docker pull redislabs/redisinsight:latest`
  - `docker run -p 8001:8001 -it --rm redislabs/redisinsight`

... or use the redis CLI in docker:

  `docker run -it --network host --rm redis redis-cli -h 127.0.0.1`

### Concurrent query runner

Two basic queries exist in the query runner in this repo called `productRecommendationQueryRunner`. Like the generator, it has configurable properties which can be output via `productRecommendationQueryRunner -h`:

```
Concurrent RedisGraph Query Runner
 -db,--database <arg>      The RedisGraph database to use for the query [defaults to prodrec]
 -h,--help                 Usage Information
 -rh,--redisHost <arg>     The host of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to null]
 -rp,--redisPort <arg>     The port of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to null]
 -tc,--threadCount <arg>   The thread count to use [defaults to 6]
```

This query runner executes two queries;

  - One to get the top 1,000 order placing people ids -- `match (p:person)-[:transact]->(o:order) return p.id, count(o) as orders order by orders desc limit 1000`
  - One to get the products found in the placed orders of other users based on the orders and products for a given user -- `match (p:person)-[:transact]->(:order)-[:contain]->(:product)<-[:contain]-(:order)-[:contain]->(prd:product) where p.id=${personId} return distinct prd.id, prd.name` (with no limits -- BAD)

## Notes

I'm trying to keep things as tidy as possible in these scripts, despite the amount of code, and I'm not catching exceptions for things like, if RedisGraph crashes, becomes overwhelmed, etc...
