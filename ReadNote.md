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

## NIO Channel VS Java Stream
    NIO Channel 类似 Java Stream，但又有几点不同：
- 对于同一个 Channel，我们可以从它读取数据，也可以向它写入数据。而对于同一个 Stream，通常要么只能读，要么只能写，二选一(有些文章也描述成“单向”，也是这个意思)。
- Channel 可以非阻塞的读写 IO 操作，而 Stream 只能阻塞的读写 IO 操作。
- Channel 必须配合 Buffer 使用，总是先读取到一个 Buffer 中，又或者是向一个 Buffer 写入。也就是说，我们无法绕过 Buffer ，直接向 Channel 写入数据。

## Channel的实现
    Channel 在 Java 中，作为一个接口，java.nio.channels.Channel，定义了 IO 操作的连接与关闭：
```java
public interface Channel extends Closeable {

    /**
     * 判断此通道是否处于打开状态 
     */
    public boolean isOpen();

    /**
     *关闭此通道
     */
    public void close() throws IOException;
}
```
    Channel 有非常多的实现类，最为重要的四个 Channel 实现类如下：

- SocketChannel：一个客户端用来发起 TCP 的 Channel
- ServerSocketChannel：一个服务端用来监听新进来的连接的 TCP 的 Channel；对于每一个新进来的连接，都会创建一个对应的 SocketChannel
- DatagramChannel：通过 UDP 读写数据
- FileChannel：从文件中，读写数据
    
    
    netty主要使用的是TCP协议，因此主要是SocketChannel和ServerSocketChannel，这两类需要特别关注
    
## java.nio.channels.SocketChannel
    Java NIO中的SocketChannel是一个连接到TCP网络套接字的通道。可以通过以下2种方式创建SocketChannel：
1. 打开一个SocketChannel并连接到互联网上的某台服务器
2. 一个新连接到达ServerSocketChannel时，会创建一个SocketChannel

### 打开SocketChannel
    下面是SocketChannel的打开方式：
```java
SocketChannel socketChannel = SocketChannel.open();
socketChannel.connect(new InetSocketAddress("http://jenkov.com", 80));
```

### 关闭SocketChannel
    当用完SocketChannel之后调用SocketChannel.close()关闭SocketChannel：
```java
socketChannel.close();
```

### 从 SocketChannel 读取数据
    要从SocketChannel中读取数据，可以通过调用read()完成：：
```java
ByteBuffer buf = ByteBuffer.allocate(48);
int bytesRead = socketChannel.read(buf);
```
    首先，分配一个Buffer。从SocketChannel读取到的数据将会放到这个Buffer中
    然后，调用SocketChannel.read()。该方法将数据从SocketChannel 读到Buffer中。read()方法返回的int值表示读了多少字节进Buffer里。如果返回的是-1，表示已经读到了流的末尾(连接关闭了)。

### 写入 SocketChannel
    写数据到SocketChannel用的是SocketChannel.write()方法，该方法以一个Buffer作为参数：
```java
String newData = "New String to write to file..." + System.currentTimeMillis();

ByteBuffer buf = ByteBuffer.allocate(48);
buf.clear();
buf.put(newData.getBytes());

buf.flip();

while (buf.hasRemaining()) {
    channel.write(buf);
}
```
    SocketChannel.write()方法的调用是在一个while循环中的。write()方法无法保证能写多少字节到SocketChannel。所以，我们重复调用write()直到Buffer没有要写的字节为止。

### 非阻塞模式
    可以设置 SocketChannel 为非阻塞模式(non-blocking mode)。设置之后，就可以在异步模式下调用connect()，read() 和write()了

### connect
    如果SocketChannel在非阻塞模式下，此时调用connect()，该方法可能在连接建立之前就返回了。为了确定连接是否建立，可以调用finishConnect()的方法：
```java
socketChannel.configureBlocking(false);
socketChannel.connect(new InetSocketAddress("http://jenkov.com", 80));

while (!socketChannel.finishConnect()) {
    // wait, or do something else...
}
```

### write
    非阻塞模式下，write()方法在尚未写出任何内容时可能就返回了。所以需要在循环中调用write()

### read
    非阻塞模式下，read()方法在尚未读取到任何数据时可能就返回了。所以需要关注它的int返回值，它会告诉你读取了多少字节

### 非阻塞模式与选择器
    非阻塞模式与选择器搭配会工作的更好，通过将一或多个SocketChannel注册到Selector，可以询问选择器哪个通道已经准备好了读取，写入等


## java.nio.channels.ServerSocketChannel
    Java NIO中的 ServerSocketChannel 是一个可以监听新进来的TCP连接的通道，就像标准IO中的ServerSocket一样。ServerSocketChannel类在 java.nio.channels包中：
```java
ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
serverSocketChannel.socket().bind(new InetSocketAddress(9999));

while (true) {
    SocketChannel socketChannel = serverSocketChannel.accept();
    // do something with socketChannel...
}
```

### 打开 ServerSocketChannel
    通过调用 ServerSocketChannel.open() 方法来打开ServerSocketChannel：
```java
ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
```
### 关闭 ServerSocketChannel
    通过调用ServerSocketChannel.close() 方法来关闭ServerSocketChannel：
```java
serverSocketChannel.close();
```

### 监听新进来的连接
    通过 ServerSocketChannel.accept() 方法监听新进来的连接。当 accept()方法返回的时候，它返回一个包含新进来的连接的 SocketChannel。因此，accept()方法会一直阻塞到有新连接到达。
    通常不会仅仅只监听一个连接，在while循环中调用 accept()方法：
```java
while (true) {
    SocketChannel socketChannel = serverSocketChannel.accept();
    // do something with socketChannel...
}
```

### 非阻塞模式
    ServerSocketChannel可以设置成非阻塞模式。在非阻塞模式下，accept() 方法会立刻返回，如果还没有新进来的连接，返回的将是null。
    因此，需要检查返回的SocketChannel是否是null：
```java
ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
serverSocketChannel.socket().bind(new InetSocketAddress(9999));
serverSocketChannel.configureBlocking(false);

while (true) {
    SocketChannel socketChannel = serverSocketChannel.accept();
    if (socketChannel != null) {
        // do something with socketChannel...
    }
}
```

## netty百万连接数调优

1.如果QPS过高，数据传输过快的情况下，调用writeAndFlush可以考虑拆分成多次write，然后单次flush，也就是批量flush操作
2.分配和释放内存尽量在reactor线程内部做，这样内存就都可以在reactor线程内部管理
3.尽量使用堆外内存，尽量减少内存的copy操作，使用CompositeByteBuf可以将多个ByteBuf组合到一起读写
4.外部线程连续调用eventLoop的异步调用方法的时候，可以考虑把这些操作封装成一个task，提交到eventLoop，这样就不用多次跨线程
5.尽量调用ChannelHandlerContext.writeXXX()方法而不是channel.writeXXX()方法，前者可以减少pipeline的遍历
6.如果一个ChannelHandler无数据共享，那么可以搞成单例模式，标注@Shareable，节省对象开销对象
7.如果要做网络代理类似的功能，尽量复用eventLoop，可以避免跨reactor线程