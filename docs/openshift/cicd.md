## CI/CD 模块

### Jenkins 安装
为了集成了jenkins组件, openshift 对jenkins 作了插件开发, 使用户可以以openshift的帐号一站式使用jenkins.
这些插件包括: [链接](https://github.com/openshift/jenkins)

- OpenShift Client Plugin: 作为oc客户端连接api server操作pipeline等对象
- OpenShift Sync Plugin: oc上的构建对象如buildConfig能够同步到jenkins里
- OpenShift Login Plugin: 使用oc上的帐号统一登陆到jenkins

下面以使用nfs作为存储保存jenkins 运行数据作为例子,展示安装过程.

- 配置NFS Server （node02-inner）

~~~
    # vi /etc/exports
    /diskb/export/jenkins-001 172.26.7.0/8(rw,sync,all_squash)
    
    # systemct restart nfs
~~~

- 创建jenkin pv, 该卷被挂载到jenkins容器/var/lib/jenkins中. 前提Nfs server端目录已经创建好.

~~~
    # oc create -f jenkins-pv-nfs.yml
~~~

- 使用jenkins-persistent 模板创建deployment config

~~~
    # oc project hyperion
    # oc process jenkins-persistent -n openshift \
    -v JENKINS_SERVICE_NAME=jenkins-persistant,JNLP_SERVICE_NAME=jenkins-jnlp-persistant \
    | oc create -f -
~~~

- 访问jenkins页面 https://jenkins-persistant-hyperion.apps.openshift.net.cn



