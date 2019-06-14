## CI/CD 模块

### Jenkins 安装
为了集成了jenkins组件, openshift 对jenkins 作了插件开发, 使用户可以以openshift的帐号一站式登录使用jenkins.
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

---
### 改造jenkins能够使用pipeline docker agent

默认的安装的jenkins镜像里没有docker 命令binary, 也没有docker 执行环境.
需要做以下改造使得此pipeline可用, 简称dind (docker in docker)
- 重新打包镜像 *openshift/jenkins-2-centos7:v3.11*
- 挂载宿主机的/var/run/docker.sock入容器

- 打包新镜像

~~~
    # docker build -t kennethye/jenkins-2-centos7:v3.11.1 -f Dockerfile.jenkins.repack .
    # docker push kennethye/jenkins-2-centos7:v3.11.1
~~~

- 修改dc配置

~~~
    # oc scale dc jenkins-persistant --replicas=0
    # oc adm policy add-scc-to-user hostmount-anyuid -z jenkins-persistant
    # oc set volume dc/jenkins-persistant --add --overwrite --name=var-run-docker --type=hostPath --path=/var/run/docker.sock
    # oc patch dc/jenkins-persistant -p '{"spec":{"template":{"spec":{"containers":[{"name":"jenkins","image": "kennethye/jenkins-2-centos7:v3.11.1", volumeMounts": [{"name": "var-run-docker", "mountPath": "/var/run/docker.sock"}] }]}}}}'
    # oc scale dc jenkins-persistant --replicas=1
~~~

---
### 改造jenkins-agent-maven能够运行docker in docker (dind)

默认的jenkins-agent-maven镜像没有docker客户端, 为了使容器化的jenkins agent能够具备镜像打包功能,
需要对它进行二次打包.

- 打包新镜像

~~~
    # docker build -t kennethye/jenkins-agent-maven-35-centos7:v3.11.1 -f ./Dockerfile-jenkins-agent-maven-3.5 .
    # docker push kennethye/jenkins-agent-maven-35-centos7:v3.11.1
~~~

---
### 添加jenkins slave进入jekinks集群步骤

把jenkins slave以容器的形式运行, 加入master组合成完整的jenkins集群

- 修改jenkins-persistant服务,添加jnlp port

![jnlp port](../_static/service-port-jnlp.png)

- 参考[教程](https://www.youtube.com/watch?v=GQjvUAmhBX8), 在jenkins master添加node

![new node](../_static/jenkins-slave-new-node.png)

![get new node secret](../_static/jenkins-slave-new-node-secret.png)

- 创建完成后, 保存以下信息

~~~
    JENKINS_URL=http://jenkins-persistant.hyperion.svc (master的URL, 50000端口要打开)
    JENKINS_SECRET=f6cxxxxxx    (上一步创建node返回的密码信息)
    JENKINS_NAME=maven-slaves   (上一步创建node的名字)
~~~

- 在slave node节点启动docker, cpu内存的大小按实际分配. 注意不同node节点的secret和名字是不一样的.
必须在master上已经有记录,才能注册得上. 

~~~
    # docker run -d --restart always --name jenkins-agent-maven \
    -v /var/run/docker.sock:/var/run/docker.sock:rw \
    --cpu-shares 1024 --memory 2G -e 'JENKINS_URL=http://jenkins-persistant.hyperion.svc' \
    -e 'JENKINS_SECRET=f6cxxxxxx' -e 'JENKINS_NAME=maven-slaves' \
    kennethye/jenkins-agent-maven-35-centos7:v3.11.1
~~~

### 搭建业务应用流水线

支撑业务（java）应用从源码, UT, 打包与上传docker镜像, 创建/更新template, 部署到openshift平台.
以下例子镜像存储使用自带的 Openshift Registry, 支持改造成Harbor等其它三方镜像仓库.

- 创建 _jenkins_ 用户, 用于Docker镜像的上传与下载. 登陆一次,使用户信息能同步到etcd.

~~~
    # htpasswd -b /etc/origin/master/htpasswd jenkins <password>
    # oc login -u jenkins -p <password> https://portal.openshift.net.cn:8443
    # oc logout
~~~

- 授予用户访问镜像仓库的权限. 每个项目都分别绑定 _registry-editor_ 权限, 才能使用 _jenkins_ 用户上传/下载项目下的镜像.

~~~
    # oc policy add-role-to-user registry-editor jenkins -n hyperion
~~~

- 创建jenkins job包含以下几个Parameter

~~~
    BUILD_NODE_LABEL	
    PROJECT_NAME	
    GIT_REPO	
    APPLICATION_TYPE	
    BRANCH	
    OC_DEV_USER	
    OC_DEV_PASS	
    REGISTRY_USER		
    REGISTRY_PASSWORD	
    ENV	
    VERSION	
    SKIP_BUILD	
    SKIP_TEST	
    APPLICATION_INIT
~~~
![Pipeline Parameters](../_static/pipeline-test003-01.png)

- 在jenkins job中导入groovy 脚本 _cicd/jenkinsfile-all-in-one.groovy_

- 以 [ft-rest-service](https://github.com/yekaifeng/ft-rest-service)为例子, 普通Spring Boot
应用需要Dockerfile容器化, 并建立适配openshift模板, 才能上云. 以下为相应改动:

~~~
    Dockerfile:
    1. 抽取JAVA_OPTIONS, APP_OPTS, JMX_OPTS, GCLOG_OPTS, 支持应用按需要配置
    2. Expose 开放端口
    3. 支持通过环境变量SPRING_PROFILES_ACTIVE传参, 设置执行环境(dev/test/prod)
    
    Openshift.yml模板:
    1. 支持设置CPU, 内存 request/limit
    2. 根据当前应用设置合理默认值, 如APPLICATION_NAME, IMAGE, SUB_DOMAIN
    3. Service设置prometheus监控annotation
~~~

- Jenkins中执行 _Build with Parameters_ 即可完成项目从源码构建, UT, 打包容器镜像, 上传镜像, 
构建openshit模板, 部署应用到云平台的完整过程.

![Build Ship Run !](../_static/build-ship-run.png)

### 让业务应用流水线展示在Openshit页面上
上述流水线构建只能在jenkin 页面上能看到, 如果通过设置BuildConfig jenkinsPipelineStrategy构建类型,
即可在openshift页面上触发, 展示整个构建的过程, 使CICD的管理更统一.

- 准备BuildConfig文件, 里面包含通过环境变量传给jenkin pipeline的所有默认配置

~~~
    kind: "BuildConfig"
    apiVersion: "v1"
    metadata:
      name: "pipeline-ft-rest-service"
    spec:
      source:
        git:
          uri: "https://github.com/yekaifeng/openshift-cn.git"  (pipeline文件所在的git repo)
      strategy:
        jenkinsPipelineStrategy:    （Pipeline Build构建类型）
          env:
            # Node label of jenkins slave
            - name: "BUILD_NODE_LABEL"    （传给pipeline的各种环境变量）
              value: "maven"
            # Openshift project of current app belongs to
            - name: "PROJECT_NAME"
              value: "hyperion"
          ......
          jenkinsfilePath: sample/env-test/cicd/jenkinsfile-all-in-one.groovy （pipeline所在目录）
~~~

- 创建Build Config: pipeline-ft-rest-service

~~~
    # oc create -f sample/env-test/cicd/bc-pipeline-ft-rest-service.yml -n hyperion
~~~

- 在页面中Builds --> Pipelines 中, 点击Start Pipeline

![Start Pipeline](../_static/pipeline-start.png)

- 构建过程被展示在当前openshift页面. 同时Jenkins页面也能够看到相同的结果.

![Build Process](../_static/pipeline-in-bc.png)











