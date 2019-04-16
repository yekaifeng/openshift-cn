## 问题汇总及解决方法

### 阿里云镜像不支持selinux enforcing

阿里云上的centos 7.6 镜像默认disabled selinux, 如果设成enforcing的话, 会导致重启主机不能登陆. 解决办法是设置成Permissive.

~~~
    # cat /etc/selinux/config
      SELINUX=permissive
    # reboot
~~~
