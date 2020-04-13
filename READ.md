## ServerSocket服务启动的本质
1. 创建Selector-->io.netty.channel.nio.NioEventLoop.openSelector
----Selector是在new NioEventLoopGroup时创建的

2. 创建ServerSocketChannel--> ReflectiveChannelFactory-->反射调用NioServerSocketChannel构造函数-->java.nio.channels.spi.SelectorProvider.openServerSocketChannel

3. 将ServerSocketChannel绑定到Selector-->java.nio.channels.SelectableChannel.register(java.nio.channels.Selector, int, java.lang.Object)
----第一次register不是监听OP_ACCEPT 而是0 只是为了获取SelectionKey
----NioEventLoop是通过register操作的执行来完成启动的

4. 将地址绑定 channel从非active转换为active状态-->java.nio.channels.ServerSocketChannel.bind(java.net.SocketAddress, int)
----类似ChannelInitializer 一些handler可以设计为一次性的

5. 注册OP_ACCEPT事件-->java.nio.channels.SelectionKey.interestOps(int)
----最终监听OP_ACCEPT是通过bind完成后的io.netty.channel.DefaultChannelPipeline.fireChannelActive触发的

## Socket服务启动的本质
---- 创建连接的初始化和注册是通过pipeline.fireChannelRead在ServerBootstrapAccepor中完成的
---- 首次register不是监听OP_READ 而是0
---- 最终监听OP_READ是通过register完成后的fireChannelActive
---- worker的NioEventLoop是通过register操作启动的
---- 接受连接的读操作不会尝试更多次(16次)

1. io.netty.channel.nio.NioEventLoop.selectNow/io.netty.channel.nio.NioEventLoop.select/java.nio.channels.Selector.select(long)轮询事件-->处理OP_ACCEPT事件
2. java.nio.channels.ServerSocketChannel.accept-->ServerSocketChannel中的NioEventLoop(Selector组件)接收连接请求创建Socket连接
3. java.nio.channels.SelectableChannel.register(java.nio.channels.Selector, int, java.lang.Object)
4. java.nio.channels.SelectionKey.interestOps(int)-->监听OP_READ事件
