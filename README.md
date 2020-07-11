# redis-graph-commerce-poc
Proof of concept work to generate a realistic commerce graph using [RedisGraph](http://redisgraph.io) and [OpenCypher](http://opencypher.org).

To leverage; run a local instance of redis-graph or use the docker image:

  - `docker pull redislabs/redisgraph`
  - `docker run -p 6379:6379 -it --rm -v redis-data:/data redislabs/redisgraph:latest`
  
You can also run redisgraph using a local directory instead of a Docker volume via:  

`docker run -p 6379:6379 -it --rm --volume=$HOME/redisgraph:/data redislabs/redisgraph:latest`

### Generate the graph

Run the groovy script in this directory -- it requires Java 11 and Groovy version 3. The grape directives pull down Maven artifacts which is how we're able to pour so much logic into a script like this.

Running `./generateCommerceGraph -h` will generate the help menu for the generation script which, at the time of this writing is:

```
usage: generateCommerceGraph
Commerce Graph Generator
 -db,--database <arg>                                         The RedisGraph database to use for our queries, data generation [defaults to prodrec]
 -h,--help                                                    Usage Information
 -maxatct,--maxRandomTimeFromViewToAddToCartInMinutes <arg>   The max random time from view to add-to-cart for a given user and product [defaults to 4320]
 -maxpd,--maxPastDate <arg>                                   The max date in the past for the memberSince field of a given user [defaults to 7300]
 -maxppp,--maxPerProductPrice <arg>                           The max price per product [defaults to 1000.00]
 -maxpurt,--maxRandomTimeFromAddToCartToPurchased <arg>       The max random time from add-to-cart to purchased for a given user and product[defaults to 4320]
 -maxship,--maxShipRate <arg>                                 The max ship rate for an order [defaults to 0.15]
 -maxtax,--maxTaxRate <arg>                                   The max tax rate for an order [defaults to 0.125]
 -maxv,--maxPotentialViews <arg>                              The max number of potential views a person can have against a product [defaults to 25]
 -minppp,--minPerProductPrice <arg>                           The min price per product [defaults to 0.99]
 -minship,--minShipRate <arg>                                 The min ship rate for an order [defaults to 0.0]
 -mintax,--minTaxRate <arg>                                   The min tax rate for an order [defaults to 0.0]
 -minv,--minPotentialViews <arg>                              The min number of potential views a person can have against a product [defaults to 0]
 -ncbs,--nodeCreationBatchSize <arg>                          The batch size to use for writes when creating people and products [defaults to 500]
 -numpeeps,--numberOfPeopleToCreate <arg>                     The number of people to create [defaults to 5000]
 -numprods,--numberOfProductsToCreate <arg>                   The number of products to create [defaults to 1000]
 -peratc,--percentageOfViewsToAddToCart <arg>                 The percentage of views to turn into add-to-cart events for a given person and product [defaults to 25]
 -perpur,--percentageOfAddToCartToPurchase <arg>              The percentage of add-to-cart into purchase events for a given person and product [defaults to 90]
 -perrate,--percentageOfPurchasesToRate <arg>                 The percentage of purchases to rate [defaults to 35]
 -rh,--redisHost <arg>                                        The host of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to localhost]
 -rp,--redisPort <arg>                                        The port of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to 6379]
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
usage: productRecommendationQueryRunner <args>
Concurrent RedisGraph Query Runner
 -db,--database <arg>        The RedisGraph database to use for the query [defaults to prodrec]
 -h,--help                   Usage Information
 -l,--limitResults <arg>     The default results limit.
 -rh,--redisHost <arg>       The host of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to localhost]
 -rp,--redisPort <arg>       The port of the Redis instance with the RedisGraph module installed to use for graph creation. [defaults to 6379]
 -tc,--threadCount <arg>     The thread count to use [defaults to 6]
 -tp,--topPurchasers <arg>   The number of top purchasers to query for [defaults to 5000]
```

This query runner executes two queries;

  - One to get the top 1,000 order placing people ids -- `match (p:person)-[:transact]->(o:order) return p.id, count(o) as orders order by orders desc limit 1000`
  - One to get the products found in the placed orders of other users based on the orders and products for a given user -- `match (p:person)-[:transact]->(:order)-[:contain]->(:product)<-[:contain]-(:order)-[:contain]->(prd:product) where p.id=${personId} return distinct prd.id, prd.name` (with no limits -- BAD)

## Notes

I'm trying to keep things as tidy as possible in these scripts, despite the amount of code, and I'm not catching exceptions for things like, if RedisGraph crashes, becomes overwhelmed, etc...

## Neo4j

Neo4j versions were additionally created. Neo4j can be run via:

`docker run --publish=7474:7474 --publish=7687:7687 --volume=$HOME/neo4j/data:/data neo4j`

Once Neo4j is started, enter the container and set the `neo4j` user password to the default in the script:

  - Get the docker instance id for neo4j:
```
➜  redis-graph-commerce-poc git:(master) ✗ docker ps
CONTAINER ID        IMAGE                         COMMAND                  CREATED             STATUS              PORTS                                                      NAMES
7aa4e6de8db3        neo4j                         "/sbin/tini -g -- /d…"   58 seconds ago      Up 56 seconds       0.0.0.0:7474->7474/tcp, 7473/tcp, 0.0.0.0:7687->7687/tcp   gracious_hopper
4fdd8b583577        redislabs/redisinsight        "bash ./docker-entry…"   2 days ago          Up 2 days           0.0.0.0:8001->8001/tcp                                     pedantic_fermat
4fc0131de216        redis                         "docker-entrypoint.s…"   2 days ago          Up 2 days                                                                      quirky_lichterman
893896de9348        redislabs/redisgraph:latest   "docker-entrypoint.s…"   2 days ago          Up 2 days           0.0.0.0:6379->6379/tcp                                     amazing_ellis
```
  - Enter the container using `/bin/bash` and run the `neo4j-admin` utility to set the initial password:
```
➜  redis-graph-commerce-poc git:(master) ✗ docker exec -it 7aa4e6de8db3 /bin/bash
root@7aa4e6de8db3:/var/lib/neo4j# ./bin/neo4j-admin set-initial-password heO2thoDac0ZtbJDAY
Changed password for user 'neo4j'.
root@7aa4e6de8db3:/var/lib/neo4j# exit
exit
```

### Graph generation

Running `./generateCommerceGraphNeo4j --help`:

```
usage: generateCommerceGraph
Commerce Graph Generator
 -h,--help                                                    Usage Information
 -maxatct,--maxRandomTimeFromViewToAddToCartInMinutes <arg>   The max random time from view to add-to-cart for a given user and product [defaults to 4320]
 -maxpd,--maxPastDate <arg>                                   The max date in the past for the memberSince field of a given user [defaults to 7300]
 -maxppp,--maxPerProductPrice <arg>                           The max price per product [defaults to 1000.00]
 -maxpurt,--maxRandomTimeFromAddToCartToPurchased <arg>       The max random time from add-to-cart to purchased for a given user and product[defaults to 4320]
 -maxship,--maxShipRate <arg>                                 The max ship rate for an order [defaults to 0.15]
 -maxtax,--maxTaxRate <arg>                                   The max tax rate for an order [defaults to 0.125]
 -maxv,--maxPotentialViews <arg>                              The max number of potential views a person can have against a product [defaults to 25]
 -minppp,--minPerProductPrice <arg>                           The min price per product [defaults to 0.99]
 -minship,--minShipRate <arg>                                 The min ship rate for an order [defaults to 0.0]
 -mintax,--minTaxRate <arg>                                   The min tax rate for an order [defaults to 0.0]
 -minv,--minPotentialViews <arg>                              The min number of potential views a person can have against a product [defaults to 0]
 -ncbs,--nodeCreationBatchSize <arg>                          The batch size to use for writes when creating people and products [defaults to 500]
 -np,--neo4jPassword <arg>                                    The neo4j password to use for authentication. [defaults to heO2thoDac0ZtbJDAY]
 -nu,--neo4jUsername <arg>                                    The neo4j username to use for authentication. [defaults to neo4j]
 -numpeeps,--numberOfPeopleToCreate <arg>                     The number of people to create [defaults to 5000]
 -numprods,--numberOfProductsToCreate <arg>                   The number of products to create [defaults to 1000]
 -nuri,--neo4jUri <arg>                                       The connect string, URI, to use for connecting to neo4j. [defaults to neo4j://localhost]
 -peratc,--percentageOfViewsToAddToCart <arg>                 The percentage of views to turn into add-to-cart events for a given person and product [defaults to 25]
 -perpur,--percentageOfAddToCartToPurchase <arg>              The percentage of add-to-cart into purchase events for a given person and product [defaults to 90]
 -perrate,--percentageOfPurchasesToRate <arg>                 The percentage of purchases to rate [defaults to 35]
 -tc,--threadCount <arg>                                      The thread count to use [defaults to 6]
```

### Query 

Running `./productRecommendationQueryRunnerNeo4j --help`:

```
usage: productRecommendationQueryRunnerNeo4j <args>
Concurrent RedisGraph Query Runner
 -h,--help                   Usage Information
 -l,--limitResults <arg>     The default results limit.
 -np,--neo4jPassword <arg>   The neo4j password to use for authentication. [defaults to heO2thoDac0ZtbJDAY]
 -nu,--neo4jUsername <arg>   The neo4j username to use for authentication. [defaults to neo4j]
 -nuri,--neo4jUri <arg>      The connect string, URI, to use for connecting to neo4j. [defaults to neo4j://localhost]
 -tc,--threadCount <arg>     The thread count to use [defaults to 6]
 -tp,--topPurchasers <arg>   The number of top purchasers to query for [defaults to 5000]
```
