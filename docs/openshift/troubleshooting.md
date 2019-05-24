## 问题汇总及解决方法

### 阿里云镜像不支持selinux enforcing

阿里云上的centos 7.6 镜像默认disabled selinux, 如果设成enforcing的话, 会导致重启主机不能登陆. 解决办法是设置成Permissive.

~~~
    # cat /etc/selinux/config
      SELINUX=permissive
    # reboot
~~~

### https服务不能用浏览器访问, 错误 *ERR_CONNECTION_RESET*
因为阿里云的限制, 不能直接用433端口访问, 临时办法是其中一台节点上安装vnc server, 远程访问

- 参考[安装vnc server](https://qizhanming.com/blog/2018/03/06/how-to-config-vnc-server-on-centos-7)
注意centos 7.6的vnc server有bug, 必须同时安装 GNOME才能启动成功.

~~~
    # yum groupinstall 'GNOME Desktop'
    # systemctl start vncserver@:1
    # iptables -I INPUT -p tcp -m state --state NEW -m tcp --dport 5901 -j ACCEPT
    # 修改阿里云网络规则开放tcp 5901端口
~~~

### Spring Metrics不能构建成功
按照[官档](https://docs.spring.io/spring-metrics/docs/current/public/prometheus)的方式
集成prometheus, _mvn build_ 构建总不能成功. 错误信息如下.

~~~
    2018-07-26 16:06:12.312 ERROR 7582 --- [           main] o.s.boot.SpringApplication               : Application run failed
    org.springframework.beans.factory.BeanDefinitionStoreException: Failed to process import candidates for configuration class [com.example.demo.DemoApplication]; nested exception is java.lang.IllegalStateException: Failed to introspect annotated methods on class io.prometheus.client.spring.boot.PrometheusEndpointConfiguration
~~~

原来这是一个bug, 这功能根本不能用. 相关[issue](https://github.com/prometheus/client_java/issues/405)

~~~
    [SpringBoot2] Cannot get SpringBoot 2 to work with Prometheus #405
    https://github.com/prometheus/client_java/issues/405
~~~

### POD创建一直处于pending状态, cni-server报 connection refuse错误
POD一直创建不成功, 有以下错误信息

~~~
    Warning  FailedCreatePodSandBox  20m     kubelet, node01-inner  Failed create pod sandbox: rpc error:
    code = Unknown desc = [failed to set up sandbox container "d422c351dbd77432a6204db362f82c0a4009eeb230987e1ad2b3fbca2f27c476"
    network for pod "logging-es-data-master-15qapabn-2-mhdq8": NetworkPlugin cni failed to set up pod
    "logging-es-data-master-15qapabn-2-mhdq8_openshift-logging" network: failed to send CNI request: Post http://dummy/:
    dial unix /var/run/openshift-sdn/cni-server.sock: connect: connection refused, failed to clean up sandbox container
    "d422c351dbd77432a6204db362f82c0a4009eeb230987e1ad2b3fbca2f27c476" network for pod "logging-es-data-master-15qapabn-2-mhdq8":
    NetworkPlugin cni failed to teardown pod "logging-es-data-master-15qapabn-2-mhdq8_openshift-logging" network:
    failed to send CNI request: Post http://dummy/: dial unix /var/run/openshift-sdn/cni-server.sock: connect: connection refused]
~~~

尝试手工连接 _cni-server_ socket, 同样返回 _connection refuse_

~~~
    # CNI_COMMAND=ADD curl --unix-socket /var/run/openshift-sdn/cni-server.sock -X POST http://dummy/
    curl: (7) Failed to connect to /var/run/openshift-sdn/cni-server.sock: Connection refused
~~~

cni-server 容器日志显示, 对ovs健康检查不过

~~~
    SDN healthcheck detected unhealthy OVS server, restarting
~~~

检查代码 _openshift-origin/pkg/network/node/healthcheck.go_. 发现如果健康检查不过,
就卡死在这里不动了,外包的一层是utilwait.NeverStop的loop, 所以也不退出. 从TODO信息看也证明
没有开发进程内重启机制. 只能是手工重启.

~~~
    if err != nil {
        // If OVS restarts and our health check fails, we exit
        // TODO: make openshift-sdn able to reconcile without a restart
        glog.Fatalf("SDN healthcheck detected unhealthy OVS server, restarting: %v", err)
    }
~~~

最后,解决办法是重启SDN POD, 恢复正常

~~~
    # oc delete pod sdn-5qtv4 -n openshift-sdn
~~~







