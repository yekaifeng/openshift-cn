## 日志模块

Openshift 日志模块集成了elastisearch, fluentd, kibana (efk)三个组件, 支持多实例高可用, host, ceph等多种外部存储.
以下安装步骤了三个es实例的集群,支持pod, 容器, systemd, java应用的日志收集.

### 安装步骤

参考[官方文档](https://docs.openshift.com/container-platform/3.11/install_config/aggregate_logging.html)

- 下载ansible 安装脚本

~~~
    # wget https://github.com/openshift/openshift-ansible/archive/openshift-ansible-3.11.100-1.tar.gz
    # tar xzvf openshift-ansible-3.11.100-1.tar.gz
    # cd openshift-ansible-openshift-ansible-3.11.100-1/

    # vi roles/openshift_logging_fluentd/templates/fluent.conf.j2
    39 <label @INGRESS>
    40 {% if deploy_type in ['hosted', 'secure-host'] %}
    41 <match time.**>
    42 @type detect_exceptions
    43 @label @INGRESS
    44 remove_tag_prefix time
    45 message log
    46 languages time
    47 multiline_flush_interval 0.1
    48 </match>
    49 ## filters
~~~

- 为计划安装日志模块节点打上label

~~~
    # oc label node node01-inner region/logging=true
    # oc label node node02-inner region/logging=true
    # oc label node node03-inner region/logging=true
    # oc label node node01-inner "region/logging-node"="1"
    # oc label node node02-inner "region/logging-node"="2"
    # oc label node node03-inner "region/logging-node"="3"
~~~

- 配置ansible hosts文件,增加日志组件相关参数

~~~
    # vi /etc/ansible/hosts.3.11

    # 安装Logging：默认不安装
    # 增加es cluster size, kibana node selector
    openshift_logging_install_logging=true
    openshift_logging_curator_nodeselector={'region/logging':'true'}
    openshift_logging_es_nodeselector={'region/logging':'true'}
    openshift_logging_kibana_nodeselector={'region/logging':'true'}
    openshift_logging_curator_run_timezone=Asia/Shanghai
    openshift_logging_es_memory_limit=4Gi
    openshift_logging_es_cluster_size=3
    openshift_logging_es_number_of_replicas=1
    openshift_logging_es_number_of_shards=3
    openshift_logging_kibana_replicas=1
    
    
    # 指定fluentd 缺省用xpmotors定制打包的镜像，指定es, kibana镜像
    openshift_logging_elasticsearch_image=openshift/origin-logging-elasticsearch:v3.11.0
    openshift_logging_kibana_image=xpmotors/origin-logging-kibana:v3.11.2
    openshift_logging_kibana_proxy_image=openshift/oauth-proxy:v1.0.0
    openshift_logging_fluentd_image=xpmotors/origin-logging-fluentd:v3.9.2
~~~

- 所有es主机创建日志存储目录, 用hostpath存储日志

~~~
    # ansible logging -m shell -a "mkdir -p /diskb/hyperion/es"
    # ansible logging -m shell -a "chmod -R 777 /diskb/hyperion/es"
~~~

- 修改内核参数

~~~
    # ansible logging -m shell -a "sysctl -w vm.max_map_count = 262144"
    # ansible logging -m shell -a "echo 'vm.max_map_count = 262144' >> /etc/sysctl.conf"
~~~

- 执行安装

~~~
    # ansible-playbook -i /etc/ansible/hosts.3.11 playbooks/openshift-logging/config.yml
~~~

- 修改ES的SA，赋权，从而可挂载本地volume

~~~
   # oc adm policy add-scc-to-user privileged system:serviceaccount:openshift-logging:aggregated-logging-elasticsearch
~~~

- 修改网络，使日志中心可以被全局访问

~~~
   # oc adm pod-network make-projects-global openshift-logging
~~~

- 修改用户访问权限，使日志中心可以被特定用户访问

~~~
    # oc adm policy add-role-to-user edit hyperion -n openshift-logging
~~~

- 为所有es data master dc打上label

~~~
    for dc in $(oc get deploymentconfig --selector component=es -o name); do
        oc scale $dc --replicas=0
        oc patch $dc \
           -p '{"spec":{"template":{"spec":{"containers":[{"name":"elasticsearch","securityContext":{"privileged": true}}]}}}}'
    done
      
    deploy=$(oc get deploymentconfig --selector component=es -o name)
    deploy1=$(echo $deploy | cut -d " " -f 1)
    deploy2=$(echo $deploy | cut -d " " -f 2)
    deploy3=$(echo $deploy | cut -d " " -f 3)
     
    oc patch $deploy1 -p '{"spec":{"template":{"spec":{"nodeSelector":{"region/logging": "true","region/logging-node":"1"}}}}}'
    oc patch $deploy2 -p '{"spec":{"template":{"spec":{"nodeSelector":{"region/logging": "true","region/logging-node":"2"}}}}}'
    oc patch $deploy3 -p '{"spec":{"template":{"spec":{"nodeSelector":{"region/logging": "true","region/logging-node":"3"}}}}}'
~~~

- 作用本地mount到每个replica(假设存储被挂到每个Node的同个目录)

~~~
    for dc in $(oc get deploymentconfig --selector component=es -o name); do
        oc set volume $dc --add --overwrite --name=elasticsearch-storage --type=hostPath --path=/diskb/hyperion/es
        oc rollout latest $dc
        oc scale $dc --replicas=1
    done
~~~





