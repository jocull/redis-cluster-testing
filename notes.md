Redis conf documentation: https://raw.githubusercontent.com/redis/redis/7.0/redis.conf

Creating a cluster:

```
docker exec -it cluster-a-redis01-1 redis-cli --cluster create redis01:6379 redis02:6380 redis03:6381 --cluster-yes
```

Add a node to cluster:

```
# Connect it to the cluster
docker exec -it cluster-a-redis01-1 redis-cli --cluster add-node redis04:6382 redis01:6379 --cluster-yes
```

Rebalancing data into a new primary node:

```
# Rebalance data amongst the entire cluster
docker exec -it cluster-a-redis01-1 redis-cli --cluster rebalance redis01:6379 --cluster-use-empty-masters
```

Adding a replica to a primary node:

```
# Lookup node id to find the one you wish to replicate
docker exec -it cluster-a-redis01-1 redis-cli --cluster check redis01:6379

# Connect to the node you wish to make a replica and give it the ID of the primary
docker exec -it cluster-a-redis01-1 redis-cli -h redis04 -p 6382 cluster replicate e7f6ffa749168a6579459fe790f02182939be808
```

Remove a node from cluster:

```
# Lookup node id to find the one you want to remove
docker exec -it cluster-a-redis01-1 redis-cli --cluster check redis01:6379

# Rebalance data off the node
docker exec -it cluster-a-redis01-1 redis-cli --cluster rebalance redis01:6379 --cluster-weight a05a28cac36f3529fe267e8ff7fcecf4d0ca1c78=0

# Disconnect it from the cluster
docker exec -it cluster-a-redis01-1 redis-cli --cluster del-node redis01:6379 a05a28cac36f3529fe267e8ff7fcecf4d0ca1c78
```
