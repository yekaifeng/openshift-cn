## 存储模块
Openshift的网络存储是以Operator的形式提供服务的, 具体是[Rook](https://rook.github.io/docs/rook/v1.0/)
作为存储的统一调度器入口, 下面有各种的存储类型, 如支持Ceph, EdgeFS等. Ceph块存储,对象存储都以CRD的形式
进行灵活的配置管理. 

以下的安装过程, 使用三台宿主机的指定目录 _/diskb/ceph_ 作为OSD存储介质, 每个Ceph Agent下只有一个OSD.
该部署只能作为测试验证用途.


### Ceph 安装步骤

- 在计划存储宿主机上建立Ceph存储目录, 所有用户数据都被存储在这里. 

~~~
    # ansible all -m shell -a "mkdir -p /diskb/ceph"
    # ansible all -m shell -a "chmod -R 777 /diskb/ceph"
~~~

- 在计划存储宿主机上建立Rook存储目录, Ceph mon, Rook operator 数据都被存储在这里. 

~~~
    # ansible all -m shell -a "mkdir -p /var/lib/rook"
    # ansible all -m shell -a "chmod 777 /var/lib/rook"
~~~

- 为存储节点打上label. 同时,这几个节点也必须是Compute节点.

~~~
    # oc label node node01-inner role=storage-node
    # oc label node node02-inner role=storage-node
    # oc label node node03-inner role=storage-node
~~~

- 下载rook operator 配置文件

~~~
    # wget https://github.com/rook/rook/archive/v1.0.2.tar.gz
    # tar xzvf v1.0.2.tar.gz 
    # cd rook-1.0.2/cluster/examples/kubernetes/ceph/
~~~

- 配置 _cluster.yml_ 文件

~~~
    # 指定rook operator目录
    dataDirHostPath: /var/lib/rook
  
    # 使用hostNetwork
    network:
    # toggle to use hostNetwork
    hostNetwork: true

    # Ceph Agent 调度到指定Label的主机
    placement:
      all:
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
            - matchExpressions:
              - key: role
                operator: In
                values:
                - storage-node

    # Cepth Mgr Pod的资源需求
    mgr:
      limits:
        cpu: "500m"
        memory: "1024Mi"
      requests:
        cpu: "500m"
        memory: "1024Mi"

    # Ceph宿主机的数据目录
    directories:
    - path: /diskb/ceph
~~~

- 执行安装. 会创建一个rook-ceph项目,相应资源都在它下面

~~~
    # oc create -f common.yaml 
    # oc create -f operator-openshift.yaml 
    # oc create -f cluster.yaml 
~~~

- 为Ceph管理页面安装[路由](https://ceph-dashboard.apps.openshift.net.cn/)

~~~
    # oc create -f openshift-cn/sample/env-test/storage/route-rook-ceph-mgr-dashboard.yml
~~~

- 访问Ceph管理页面 _https://ceph-dashboard.apps.openshift.net.cn/_,
管理员admin密码用以下命令获取

~~~
    # oc -n rook-ceph get secret rook-ceph-dashboard-password -o jsonpath="{['data']['password']}" | base64 --decode && echo
~~~

### 参考资料

- [https://operatorhub.io/operator/rook-ceph](https://operatorhub.io/operator/rook-ceph)
- [https://rook.github.io/docs/rook/v1.0/](https://rook.github.io/docs/rook/v1.0/)
- [https://github.com/rook/rook/tree/release-1.0](https://github.com/rook/rook/tree/release-1.0)

