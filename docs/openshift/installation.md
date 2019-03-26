## openshift 集群安装

### 安装计划

在安装你openshift集群之前, 需要根据集群规模和资源情况, 来规划各个基础组件的部署安排. 如果有超过三台或者以上的机器,
就可以考虑高可用了. Master, Node, Etcd, Router, ES, Prometheus, Grafana 等组件都支持多实例部署. 资源许可
的情况下, etcd集群最好单独部署, 否则可以跟master部在一起. 从OKD 3.10开始, RHEL/CENTOS操作系统上以RPM形式部署,
RHEL Atomic Host 则以容器镜像形式部署.

### 系统要求

#### 所有主机

* 主机之间可以互相通信, 也能访问外网. 如果是Router机器的话, 还需要配置DNS泛域解释.
* 开启selinux
* 开启 DNS 和 NetworkManager
* iptables是默认打开的, 需要打开端口有53, 4789, 8443, 10250, 2379, 2380等.详细见[官档页面](https://docs.okd.io/3.11/install/prerequisites.html#required-ports)

#### Master

* 操作系统: Fedora 21, CentOS 7.4, Red Hat Enterprise Linux (RHEL) 7.4 或者更新
* 最少4核vCPU, 16GB内存
* 40GB 磁盘空间 （/var目录)

#### Node

* 操作系统: Fedora 21, CentOS 7.4, Red Hat Enterprise Linux (RHEL) 7.4 或者更新
* 最少1核vCPU, 8GB内存
* 15GB 磁盘空间 （/var目录)

#### 外部etcd

* 20GB 磁盘空间 （/var/lib/etcd 目录)

### 准备主机环境

一. 在阿里云申请了云主机后, 在master上设置主机名, 设置ssh key和主机间免密码登陆

~~~
    # cat /etc/hosts
    172.26.7.167	node01-inner
    172.26.100.176	node02-inner
    172.26.7.168	node03-inner

    # ssh-keygen （所有主机）
    # for host in node01-inner node02-inner node03-inner; do ssh-copy-id -i ~/.ssh/id_rsa.pub $host; done
~~~

二. 安装基础 rpm （所有主机）

~~~
    # yum install wget git net-tools bind-utils yum-utils iptables-services bridge-utils bash-completion kexec-tools sos psacct
    # yum update
    # reboot
~~~

三. 安装 Ansible （Master）

~~~
    # yum -y install https://dl.fedoraproject.org/pub/epel/epel-release-latest-7.noarch.rpm
    # sed -i -e "s/^enabled=1/enabled=0/" /etc/yum.repos.d/epel.repo
    # yum -y --enablerepo=epel install ansible pyOpenSSL
    
    # cd ~
    # wget https://github.com/openshift/openshift-ansible/archive/openshift-ansible-3.11.100-1.tar.gz
    # tar xzvf openshift-ansible-3.11.100-1.tar.gz
    # cd openshift-ansible-openshift-ansible-3.11.100-1/
~~~

四. 安装 Docker, 默认配置即可. 需要定制化options的话, 在ansible hosts文件里定义.

~~~
    # yum install docker-1.13.1
    # rpm -V docker-1.13.1
    # docker version
~~~


