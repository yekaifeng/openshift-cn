## openshift 集群配置管理

 主要用于做一些devops的部署最佳实践。
 包括内容
- openshift：云平台运维脚本
- system：测试环境中间件依赖的单机版Docker部署配置。分环境作为目录
- tools：ops相关的工具类

### 为应用部署tls sni路由证书
  服务器名称指示（英语：Server Name Indication，简称SNI）是TLS的一个扩展协议，在该协议下，
  在握手过程开始时客户端告诉它正在连接的服务器要连接的主机名称。这允许服务器在相同的IP地址和TCP
  端口号上呈现多个证书，并且因此允许在相同的IP地址上提供多个安全（HTTPS）网站（或其他任何基于TLS的服务），
  而不需要所有这些站点使用相同的证书。

一. 申请tls证书, 阿里云上有免费的,只有一年, 每个域名30个名额

二. 参考[官方文档](https://docs.okd.io/3.11/day_two_guide/certificate_management.html#admin-solutions-certificate-management)进行以下操作

~~~
  # oc project opeshift-console
  # oc export route console -o yaml > console.backup.yml
  # oc delete route console
  # oc create route reencrypt console-custom -n openshift-console \
  --hostname console.apps.openshift.net.cn --key console.apps.openshift.net.cn.key \
  --cert console.apps.openshift.net.cn.crt --ca-cert console.apps.openshift.net.cn.ca \
  --service console
  
  # oc project openshift-logging
  # oc export route logging-kibana -o yaml > route-logging-kibana.yml
  # oc delete route logging-kibana
  # oc create route reencrypt logging-kibana -n openshift-logging \
  --hostname kibana.apps.openshift.net.cn --key kibana.apps.openshift.net.cn.key \
  --cert kibana.apps.openshift.net.cn.crt --ca-cert kibana.apps.openshift.net.cn.ca \
  --service logging-kibana
~~~

---
### 配置docker-registry外挂主机目录
openshift docker registry 默认安装使用empty volume, 容器重启镜像信息不能持久化.
通过挂载宿主机目录的方法, 把镜像保存在主机文件系统中, 重启后镜像仍然保留.

- 在容器所在主机上,创建相应目录保存镜像

~~~
    # mkdir -p /diskb/registry
    # chmod 777 -R /diskb/registry
~~~

- 关闭docker registry

~~~
    # oc project default
    # oc scale dc docker-registry --replicas=0
~~~

- 提升容器权限访问主机目录

~~~
    # oc patch dc/docker-registry -p '{"spec":{"template":{"spec":{"containers":[{"name":"registry","securityContext":{"privileged": false}}]}}}}'
    # oc adm policy add-scc-to-user hostmount-anyuid -z registry
~~~

- 设置主机目录

~~~
    # oc set volume dc/docker-registry --add --overwrite --name=registry-storage --type=hostPath --path=/diskb/registry
~~~

- 恢复registry服务

~~~
    # oc scale dc docker-registry --replicas=1
~~~

### 配置项目访问外部带安全验证的镜像仓库
每个项目都要单独配置
- 创建image pull secret。带有镜像仓库登陆信息。使用红帽registry的话，建议用registry service account。

~~~
    # oc project hyperion
    # oc create secret docker-registry hyperion-pull-secret \
        --docker-server=registry.redhat.io \
        --docker-username=<user_name> \
        --docker-password=<password> \
        --docker-email=<email>
~~~

- 把secret连接到default service account, 使当前项目默认使用default sa来运行pod, 并下载镜像。

~~~
    # oc secrets link default hyperion-pull-secret --for=pull
~~~







