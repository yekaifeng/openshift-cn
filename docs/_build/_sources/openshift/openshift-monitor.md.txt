## 监控模块

Openshift v3.11 集群监控以operator的形式把prometheus, grafana, alertmanager集中管理起来.
然而,当cluster monitor operator作为最高管理者,只开放了部分api对象修改, 这就造成了二次定制开发的巨大限制.
比如我要为grafana增加volumeClaim, 直接修改deployment spec是不行的, 因为operator侦测到对象变化,
硬是又给你再改回来. 而operator层面没有把这个对象参数化, 并不提供修改的渠道. 实在是 ...

promethues只能使用storageClass作为存储对象接口, 为了支持方便的NFS, 这里需要进行一些改造.
另外grafana 暂时也只能使用empty dir作为存储, 它的插件更新无法持久化.

![Openshift cluster monitoring 架构](../_static/cluster-monitoring.png)

### 安装步骤
- 配置NFS Server （node02-inner）

~~~
    # vi /etc/exports
    /diskb/export/prometheus-001 172.26.7.0/8(rw,sync,all_squash)
    /diskb/export/prometheus-002 172.26.7.0/8(rw,sync,all_squash)
    /diskb/export/alertmanager-001 172.26.7.0/8(rw,sync,all_squash)
    /diskb/export/alertmanager-002 172.26.7.0/8(rw,sync,all_squash)
    /diskb/export/alertmanager-003 172.26.7.0/8(rw,sync,all_squash)
    /diskb/export/grafana-001 172.26.7.0/8(rw,sync,all_squash)
    
    # systemct restart nfs
    # iptables -A OS_FIREWALL_ALLOW -p tcp -m state --state NEW -m tcp --dport 2049 -j ACCEPT
~~~

- 为monitor节点打上label

~~~
    # oc label node node01-inner region/monitor=true
    # oc label node node02-inner region/monitor=true
~~~

- 修改ansible hosts文件, 增加相关配置选项. 这里定义了storage_class_name是不存在的, 目的是为了后继修改方便.

~~~
    # vi /etc/ansible/hosts

    # 安装Prometheus operator
    #
    # Cluster monitoring is enabled by default, disable it by setting
    openshift_cluster_monitoring_operator_install=true
    #
    # Cluster monitoring configuration variables allow setting the amount of
    # storage and storageclass requested through PersistentVolumeClaims.
    #
    openshift_cluster_monitoring_operator_prometheus_storage_enabled=true
    openshift_cluster_monitoring_operator_alertmanager_storage_enabled=true
    
    openshift_cluster_monitoring_operator_prometheus_storage_capacity="2Gi"
    openshift_cluster_monitoring_operator_alertmanager_storage_capacity="1Gi"
    
    openshift_cluster_monitoring_operator_node_selector={'region/monitor':'true'}
    
    # external NFS support refer to Using Storage Classes for Existing Legacy Storage
    openshift_cluster_monitoring_operator_prometheus_storage_class_name="nfs"
    openshift_cluster_monitoring_operator_alertmanager_storage_class_name="nfs"
~~~

- 修改以下playbook operator config template文件, 这是让prometheus使用NFS的一个hack

~~~
    # vi roles/openshift_cluster_monitoring_operator/templates/cluster-monitoring-operator-config.j2
    
    Line 28
    {% if openshift_cluster_monitoring_operator_prometheus_storage_enabled | bool %}
      volumeClaimTemplate:
        spec:
          selector:
            matchLabels:
              volume/type: pv-prometheus
          resources:
            requests:
              storage: {{ openshift_cluster_monitoring_operator_prometheus_storage_capacity }}
    {% endif %}

    Line 46
    {% if openshift_cluster_monitoring_operator_alertmanager_storage_enabled | bool %}
          volumeClaimTemplate:
            spec:
              selector:
                matchLabels:
                  volume/type: pv-alertmanager
              resources:
                requests:
                  storage: {{ openshift_cluster_monitoring_operator_alertmanager_storage_capacity }}
    {% endif %}
~~~

- 创建alertmanager, promethues, grafana pv/pvc

~~~
    # oc create -f prometheus-pv-nfs-001.yml
    # oc create -f prometheus-pv-nfs-002.yml
    # oc create -f grafana-pv-pvc-nfs.yml
~~~

- 执行安装

~~~
    # ansible-playbook playbooks/openshift-monitoring/config.yml
~~~

- 访问promehteus 入口页面 https://grafana-openshift-monitoring.apps.openshift.net.cn


