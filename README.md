# redis-graph-commerce-poc
Proof of concept work to generate a realistic commerce graph using Redis-graph and open cypher.

To leverage; run a local instance of redis-graph or use the docker image:

  `docker run -p 6379:6379 -it --rm -v redis-data:/data redislabs/redisgraph:edge`

Then, run the groovy script in this directory.

  `groovy 1-create_local_commerce_graph.groovy`

Finally, write queries against the graph using Redis Insight:

  `docker run -p 8001:8001 -it --rm redislabs/redisinsight`

... or use the redis CLI in docker:

  `docker run -it --network host --rm redis redis-cli -h 127.0.0.1`

## or, if using the bulk loader

`python bulk_insert.py prodrec -n person.csv -n product.csv -n order.csv -r view.csv -r addtocart.csv -r transact.csv -r contain.csv`
