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




