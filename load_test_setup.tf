variable "linode_token" {
}

provider "linode" {
  token = var.linode_token
}

resource "linode_stackscript" "redis_graph_loadtest" {
  label = "redis"
  description = "Builds and installs redis, redisgraph, and the java/groovy load testing scripts"
  script = <<EOF
#!/bin/bash

exec >/root/stackscript.log 2>/root/stackscript_error.log

# package dependencies
apt-get install build-essential cmake m4 automake peg libtool autoconf git zip unzip openjdk-11-jdk-headless htop pkg-config -y

# use sdkman to install groovy, over apt
curl -s "https://get.sdkman.io" | bash
source "/root/.sdkman/bin/sdkman-init.sh"
sdk install groovy

# pull the generation and query scripts
cd ~
wget https://raw.githubusercontent.com/joshdurbin/redis-graph-commerce-poc/master/generateCommerceGraph
chmod u+x generateCommerceGraph
wget https://raw.githubusercontent.com/joshdurbin/redis-graph-commerce-poc/master/productRecommendationQueryRunner
chmod u+x productRecommendationQueryRunner

# run the scripts async to pre-pull maven dependencies
./generateCommerceGraph 2>/dev/null &
./productRecommendationQueryRunner 2>/dev/null &

# pull redids source and build
cd ~
wget http://download.redis.io/releases/redis-6.0.4.tar.gz
tar xzf redis-6.0.4.tar.gz
cd ~/redis-6.0.4
make install
cp redis.conf /etc/redis.conf

# update redis conf to disable snapshotting
sed -e '/save/ s/^#*/#/' -i /etc/redis.conf
cd ~
rm redis-6.0.4.tar.gz
rm -Rf redis-6.0.4

# system tuning parameters
sysctl -w net.core.somaxconn=1024

# clone redisgraph and build module
cd ~
git clone --recurse-submodules -j8 https://github.com/RedisGraph/RedisGraph.git
cd ~/RedisGraph
git checkout tags/v2.0.13 -b version_2013
make
cp src/redisgraph.so /opt/

# configure redis to load the module
echo 'loadmodule /opt/redisgraph.so' >> /etc/redis.conf

# cleanup
cd ~
rm -Rf RedisGraph/

# start redis-server
redis-server /etc/redis.conf > /var/log/redis.log &

# benchmark suite
curl -s https://packagecloud.io/install/repositories/akopytov/sysbench/script.deb.sh | sudo bash
sudo apt -y install sysbench
EOF
  images = [
    "linode/debian10"]
  rev_note = "initial version"
}

resource "linode_stackscript" "neo4j_loadtest" {
  label = "neo4j"
  description = "Builds and installs neo4j and the java/groovy load testing scripts"
  script = <<EOF
#!/bin/bash

exec >/root/stackscript.log 2>/root/stackscript_error.log

# package dependencies
apt-get install zip unzip openjdk-11-jdk-headless htop pkg-config -y

# use sdkman to install groovy, over apt
curl -s "https://get.sdkman.io" | bash
source "/root/.sdkman/bin/sdkman-init.sh"
sdk install groovy

# pull the generation and query scripts
cd ~
wget https://raw.githubusercontent.com/joshdurbin/redis-graph-commerce-poc/master/generateCommerceGraphNeo4j
chmod u+x generateCommerceGraphNeo4j
wget https://raw.githubusercontent.com/joshdurbin/redis-graph-commerce-poc/master/productRecommendationQueryRunnerNeo4j
chmod u+x productRecommendationQueryRunnerNeo4j

# run the scripts async to pre-pull maven dependencies
./generateCommerceGraph 2>/dev/null &
./productRecommendationQueryRunner 2>/dev/null &

# pull redids source and build
cd ~
wget https://neo4j.com/artifact.php\?name\=neo4j-community-4.0.6-unix.tar.gz
tar -zxf artifact.php\?name=neo4j-community-4.0.6-unix.tar.gz
./neo4j-community-4.0.6/bin/neo4j-admin set-initial-password heO2thoDac0ZtbJDAY

# tuning parameters
ulimit -n 40000
./neo4j-community-4.0.6/bin/neo4j start

# benchmark suite
curl -s https://packagecloud.io/install/repositories/akopytov/sysbench/script.deb.sh | sudo bash
sudo apt -y install sysbench
EOF
  images = [
    "linode/debian10"]
  rev_note = "initial version"
}

resource "linode_sshkey" "jdurbin_platypus" {
  label = "jdurbin"
  ssh_key = chomp(file("~/.ssh/id_rsa.pub"))
}

resource "linode_instance" "redis" {
  image = "linode/debian10"
  label = "redis"
  region = "us-west"
  type = "g6-dedicated-32"
  authorized_keys = [linode_sshkey.jdurbin_platypus.ssh_key]
  root_pass = "T0YSMfOcpTCT8gfv3hPdGzHtW7myahsG"
  stackscript_id = linode_stackscript.redis_graph_loadtest.id
}

resource "linode_instance" "neo4j" {
  image = "linode/debian10"
  label = "neo4j"
  region = "us-west"
  type = "g6-dedicated-32"
  authorized_keys = [linode_sshkey.jdurbin_platypus.ssh_key]
  root_pass = "T0YSMfOcpTCT8gfv3hPdGzHtW7myahsG"
  stackscript_id = linode_stackscript.neo4j_loadtest.id
}

output "instance_ip" {
  value = "${linode_instance.redis.ip_address}"
}
