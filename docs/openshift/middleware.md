## 中间件

### 安装Redis 集群
容器化Redis有单机版，集群模式，哨兵模式。Spring Boot应用针对不同模式有对应的代码库，使用不同的模式不能通过
配置自由切换，需要修改代码支持。RedisLab仅提供哨兵模式，没有集群模式。
以下为集群模式安装例子。

- ConfigMap中初始化密码

~~~
     # vi sample/env-test/middleware/cm-redis-cluster.yml
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: redis-cluster
        data:
          update-node.sh: |
            #!/bin/sh
            REDIS_NODES="/data/nodes.conf"
            sed -i -e "/myself/ s/[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}/${POD_IP}/" ${REDIS_NODES}
            exec "$@"
          redis.conf: |+
            cluster-enabled yes
            cluster-require-full-coverage no
            cluster-node-timeout 15000
            cluster-config-file /data/nodes.conf
            cluster-migration-barrier 1
            appendonly yes
            protected-mode no
            requirepass redis123    # 设置密码保护
~~~

- 创建Redis Cluster Statefulset, 这里共有6个实例，两两主备。16384个哈希槽(hash slot) 被平均分配到三个主里。

~~~
     # oc create -f sample/env-test/middleware/cm-redis-cluster.yml
~~~

- 创建 Headerless Service, 使每个Redis实例能够以内部域名直接访问

~~~
     # vi sample/env-test/middleware/svc-redis-cluster.yml
        apiVersion: v1
        kind: Service
        metadata:
          name: redis-cluster
        spec:
          clusterIP: None  # Headless Service
          ports:
          - name: client
            port: 6379
            protocol: TCP
            targetPort: 6379
          - name: gossip
            port: 16379
            protocol: TCP
            targetPort: 16379
          selector:
            app: redis-cluster
          sessionAffinity: None
          type: ClusterIP
     # oc create -f sample/env-test/middleware/svc-redis-cluster.yml
~~~

- 在spring boot配置中，修改redis host的访问地址

~~~
        spring.redis.timeout = 30000
        spring.redis.password = redis123
        # 以逗号分割的redis集群节点配置，格式：host:port，
        spring.redis.cluster.nodes=redis-cluster-0.redis-cluster:6379,redis-cluster-1.redis-cluster:6379,
        redis-cluster-2.redis-cluster:6379,redis-cluster-3.redis-cluster:6379,redis-cluster-4.redis-cluster:6379,
        redis-cluster-5.redis-cluster:6379
~~~

- 参考[资料](https://rancher.com/blog/2019/deploying-redis-cluster/)

