## ServerSocket服务启动主线
    1. 创建Selector-->io.netty.channel.nio.NioEventLoop.openSelector  
    
    Selector是在new NioEventLoopGroup时创建的

    2. 创建ServerSocketChannel--> ReflectiveChannelFactory-->反射调用NioServerSocketChannel构造函数-->java.nio.channels.spi.SelectorProvider.openServerSocketChannel
    
    3. 将ServerSocketChannel绑定到Selector-->java.nio.channels.SelectableChannel.register(java.nio.channels.Selector, int, java.lang.Object)  
    ---- 第一次register不是监听OP_ACCEPT 而是0 只是为了获取SelectionKey  
    ---- NioEventLoop是通过register操作的执行来完成启动的
    
    4. 将地址绑定 Channel从非active转换为active状态-->java.nio.channels.ServerSocketChannel.bind(java.net.SocketAddress, int)  
    ---- 类似ChannelInitializer 一些handler可以设计为一次性的
    
    5. 注册OP_ACCEPT事件-->java.nio.channels.SelectionKey.interestOps(int)  
    ---- 最终监听OP_ACCEPT是通过bind完成后的io.netty.channel.DefaultChannelPipeline.fireChannelActive触发的

## Socket服务启动主线
    ---- 创建连接的初始化和注册是通过pipeline.fireChannelRead在ServerBootstrapAccepor中完成的  
    ---- 首次register不是监听OP_READ 而是0  
    ---- 最终监听OP_READ是通过register完成后的fireChannelActive  
    ---- worker的NioEventLoop是通过register操作启动的  
    ---- 接受连接的读操作不会尝试更多次(16次)  
    
    1. io.netty.channel.nio.NioEventLoop.selectNow/io.netty.channel.nio.NioEventLoop.select/java.nio.channels.Selector.select(long)轮询事件-->处理OP_ACCEPT事件  
    2. java.nio.channels.ServerSocketChannel.accept-->ServerSocketChannel中的NioEventLoop(Selector组件)接收连接请求创建Socket连接  
    3. java.nio.channels.SelectableChannel.register(java.nio.channels.Selector, int, java.lang.Object)  
    4. java.nio.channels.SelectionKey.interestOps(int)-->监听OP_READ事件  

## 数据读取主线
    1. 读取数据的本质通过sun.nio.ch.SocketChannelImpl.read(java.nio.ByteBuffer)完成的  
    2. NioSocketChannel的read()是读数据 NioServerSocketChannel的read()是创建连接  
    ---- 但都可以认为是读操作  
    3. io.netty.channel.ChannelPipeline.fireChannelReadComplete标志着一次OP_READ操作的完成  
    4. io.netty.channel.ChannelPipeline.fireChannelRead只是表示一次数据的读完成 一次OP_READ事件可能有多次数据读取操作  
    5. AdaptiveRecvByteBufAllocator对ByteBuf的猜测是--放大果断 缩小谨慎(连续2次判断)  

## 数据处理主线
    1. 数据业务处理的本质--数据在pipeline中所有handler的channelRead()执行过程  
    2. 符合条件的handler必须满足两个条件--a.实现io.netty.channel.ChannelInboundHandler.channelRead方法 b.channelRead方法没有添加@Skip注解  
    3. 对于handler的执行中途是可推出的 不保证执行到tailHandler  
    4. 默认处理的线程是Channel绑定的NioEventLoop线程 也可以设置为其他的--pipeline.addLast(new UnorderedThreadPoolEventExecutor(10), serverHandler) 在添加的过程中会算出一个executionMask(是否具有执行资格)  

## 数据写入主线
    1. Single Write  
    ---- java.nio.channels.SocketChannel.write(java.nio.ByteBuffer)  
    -- Gathering Write  
    ---- java.nio.channels.SocketChannel.write(java.nio.ByteBuffer[], int, int)  
    2. 写数据写不进去时会停止写 注册一个OP_WRITE事件 来通知什么时候可以继续写  
    3. `OP_WRITE并不是说有数据可写 而是说可以写进去` 所以正常情况下不能注册这个事件 否则一直触发  
    4. 批量写数据时 如果尝试写的都写进去了 接下来会尝试写更多(maxBytesPerGatheringWrite)  
    5. 只要有数据写且能写就会一直尝试 直到16次(writeSpinCount) 写16次还没有写完 就直接schedule一个task继续写 而不是用注册写事件来触发 更简洁  
    6. 待写的数据太多 超过高水位线(writeBufferWaterMask.high()) 会将可写的标志改成false 让应用端自己做决定要不要继续写  
    7. channelHandlerContext.channel().write()  --  从TailContext开始执行  
    -- channelHandlerContext.write()    -- 从当前Context开始  

## 关闭连接主线
    1. java.nio.channels.spi.AbstractInterruptibleChannel.close -- 关闭Channel  
    -- java.nio.channels.SelectionKey.cancel 将Selector上的SelectionKey cancel掉 这样Channel就不能接收到event了  
    2. 关闭连接会触发OP_READ事件 读取字节数 = -1代表关闭  
    3. 数据读取进行时强行关闭(异常退出) 触发IO EXCEPTION 进而执行关闭
    4. Channel的关闭包含了SelectionKey的cancel

## 服务端关闭主线
    1. 服务端关闭的本质就是关闭所有的连接以及Selector  
    -- java.nio.channels.Selector.keys -- 拿到所有的SelectionKey
    -- java.nio.channels.spi.AbstractSelectionKey.cancel -- 执行SelectionKey的cancel  
    -- java.nio.channels.spi.AbstractInterruptibleChannel.close -- 关闭所有的Channel  
    -- java.nio.channels.Selector.close -- 关闭Selector
    -- 关闭所有线程 退出for(;;)循环体
    2. 关闭服务的要点  
    -- 优雅 -- 静默期DEFAULT_SHUTDOWN_QUIET_PERIOD  
    -- 可控 -- DEFAULT_SHUTDOWN_TIMEOUT  
    -- 先不接任务 尽量干完手头的任务

## 在Unix IO模型语境下:
- 同步与异步的区别: 数据拷贝阶段是否需要完全由操作系统处理
- 阻塞与非阻塞的区别: 发起IO请求操作后 是否立刻返回一个标志信息而不让请求线程等待

## NIO 与 BIO 的区别主要体现在三个方面
|NIO|BIO|
|:---|:---|
|基于缓冲区(Buffer)|	基于流(Stream)|
|非阻塞 IO	|阻塞 IO|
|选择器(Selector)	|无|
- 其中Selector是NIO能实现非阻塞的基础

## 基于Buffer VS 基于Stream
    BIO 是面向字节流或者字符流的；
    而在 NIO 中，它摒弃了传统的 IO 流，而是引入 Channel 和 Buffer 的概念：从 Channel 中读取数据到 Buffer 中，或者将数据从 Buffer 中写到 Channel 中
- 基于Stream

    在一般的 Java IO 操作中，我们以**流式**的方式，**顺序**的从一个 Stream 中读取一个或者多个字节，直至读取所有字节。因为它没有缓存区，所以我们就不能随意改变读取指针的位置。   
- 基于Buffer

    基于 Buffer 就显得有点不同了。我们在从 Channel 中读取数据到 Buffer 中，这样 Buffer 中就有了数据后，我们就可以对这些数据进行操作了。并且不同于一般的 Java IO 操作那样是顺序操作，NIO 中我们可以随意的读取任意位置的数据，这样大大增加了处理过程中的灵活性。
## 阻塞IO VS 非阻塞IO
    Java IO 的各种流是阻塞的 IO 操作。这就意味着，当一个线程执行读或写 IO 操作时，该线程会被阻塞，直到有一些数据被读取，或者数据完全写入。
    Java NIO 可以让我们非阻塞的使用 IO 操作。例如：
- 当一个线程执行从 Channel 执行读取 IO 操作时，当此时有数据，则读取数据并返回；当此时无数据，则直接返回而不会阻塞当前线程。
- 当一个线程执行向 Channel 执行写入 IO 操作时，不需要阻塞等待它完全写入，这个线程同时可以做别的事情。

    也就是说，线程可以将非阻塞 IO 的空闲时间用于在其他 Channel 上执行 IO 操作。所以，一个单独的线程，可以管理多个 Channel 的读取和写入 IO 操作。
    
## Selector

Java NIO 引入 Selector (选择器)的概念，它是 Java NIO 得以实现非阻塞 IO 操作的**最最最**关键的组件。
我们可以注册多个 Channel 到一个 Selector 中。而 Selector 内部的机制，就可以自动的为我们不断的执行查询(select)操作，判断这些注册的 Channel 是否有已就绪的 IO 事件(例如可读，可写，网络连接已完成)。
通过这样的机制，一个线程通过使用一个 Selector ，就可以非常简单且高效的来管理多个 Channel 了。

## NIO VS AIO
* AIO 并没有比 NIO 快，netty已经有稳定的NIO实现了